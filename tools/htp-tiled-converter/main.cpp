#include "gguf.h"
#include "ggml.h"
#include "htp-repack.h"
#include "htp-tiled-cache.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

static uint64_t source_signature(const std::string & path, uint64_t size) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("cannot open source GGUF for fingerprinting");
    uint64_t hash = 1469598103934665603ULL;
    auto feed = [&](const void * bytes, size_t count) {
        const auto * p = static_cast<const uint8_t *>(bytes);
        for (size_t i = 0; i < count; ++i) { hash ^= p[i]; hash *= 1099511628211ULL; }
    };
    feed(&size, sizeof(size));
    constexpr size_t SAMPLE = 64 * 1024;
    const size_t count = static_cast<size_t>(std::min<uint64_t>(size, SAMPLE));
    std::vector<char> sample(count);
    if (count && !file.read(sample.data(), static_cast<std::streamsize>(count))) throw std::runtime_error("failed to read GGUF head");
    if (count) feed(sample.data(), count);
    if (size > count) {
        file.clear();
        file.seekg(static_cast<std::streamoff>(size - count));
        if (!file.read(sample.data(), static_cast<std::streamsize>(count))) throw std::runtime_error("failed to read GGUF tail");
        feed(sample.data(), count);
    }
    return hash;
}

static uint64_t packed_q4_0_size(const ggml_tensor * t) {
    const uint64_t kt = (static_cast<uint64_t>(t->ne[0]) + 31) / 32;
    const uint64_t ct = (static_cast<uint64_t>(t->ne[1]) + 31) / 32;
    const uint64_t slices = static_cast<uint64_t>(t->ne[2]) * static_cast<uint64_t>(t->ne[3]);
    if (kt && ct > std::numeric_limits<uint64_t>::max() / kt) throw std::runtime_error("tensor packed size overflow");
    const uint64_t tiles = kt * ct;
    if (tiles > std::numeric_limits<uint64_t>::max() / 576 / std::max<uint64_t>(slices, 1))
        throw std::runtime_error("tensor packed size overflow");
    return tiles * 576 * slices;
}

int main(int argc, char ** argv) {
    if (argc != 3) {
        std::cerr << "Usage: gguf-htp-tiled-convert <model.gguf> <model.gguf.htpbin>\n";
        return 2;
    }
    const std::string source = argv[1];
    const std::string output = argv[2];
    try {
        const uint64_t source_size = fs::file_size(source);
        const uint64_t signature = source_signature(source, source_size);

        ggml_context * tensor_ctx = nullptr;
        gguf_init_params params{};
        params.no_alloc = true;
        params.ctx = &tensor_ctx;
        gguf_context * metadata = gguf_init_from_file(source.c_str(), params);
        if (!metadata || !tensor_ctx) throw std::runtime_error("gguf_init_from_file failed");

        std::vector<htp_tiled_cache::Entry> entries;
        std::map<int, std::pair<uint64_t, uint64_t>> type_stats;
        const int64_t count = gguf_get_n_tensors(metadata);
        for (int64_t i = 0; i < count; ++i) {
            const char * name = gguf_get_tensor_name(metadata, i);
            ggml_tensor * tensor = ggml_get_tensor(tensor_ctx, name);
            if (!tensor) throw std::runtime_error(std::string("missing GGUF tensor metadata: ") + name);
            auto & stats = type_stats[static_cast<int>(tensor->type)];
            stats.first++;
            stats.second += gguf_get_tensor_size(metadata, i);
            if (tensor->type != GGML_TYPE_Q4_0 || !htp_is_tiled_weight_tensor(tensor)) continue;
            if (std::strlen(name) >= htp_tiled_cache::NAME_CAPACITY)
                throw std::runtime_error(std::string("tensor name too long for cache format: ") + name);
            htp_tiled_cache::Entry entry{};
            std::memcpy(entry.name, name, std::strlen(name));
            entry.type = static_cast<uint32_t>(tensor->type);
            for (int d = 0; d < 4; ++d) entry.ne[d] = static_cast<uint64_t>(tensor->ne[d]);
            entry.size = packed_q4_0_size(tensor);
            entries.push_back(entry);
        }
        for (const auto & item : type_stats) {
            std::cout << "TYPE " << ggml_type_name(static_cast<ggml_type>(item.first))
                      << " tensors=" << item.second.first << " raw_bytes=" << item.second.second << "\n";
        }
        if (entries.empty()) throw std::runtime_error("no eligible Q4_0 tensors found for HTP tiled layout");

        htp_tiled_cache::Header header{};
        std::memcpy(header.magic, htp_tiled_cache::MAGIC, sizeof(header.magic));
        header.version = htp_tiled_cache::VERSION;
        header.entry_count = static_cast<uint32_t>(entries.size());
        header.source_size = source_size;
        header.source_signature = signature;
        header.entries_offset = sizeof(header);
        header.data_offset = sizeof(header) + entries.size() * sizeof(htp_tiled_cache::Entry);
        uint64_t cursor = header.data_offset;
        for (auto & entry : entries) {
            entry.offset = cursor;
            if (entry.size > std::numeric_limits<uint64_t>::max() - cursor) throw std::runtime_error("cache file size overflow");
            cursor += entry.size;
        }

        std::ifstream input(source, std::ios::binary);
        std::ofstream out(output, std::ios::binary | std::ios::trunc);
        if (!input || !out) throw std::runtime_error("cannot open input or output file");
        out.write(reinterpret_cast<const char *>(&header), sizeof(header));
        out.write(reinterpret_cast<const char *>(entries.data()),
                  static_cast<std::streamsize>(entries.size() * sizeof(htp_tiled_cache::Entry)));
        if (!out) throw std::runtime_error("failed to write cache index");

        uint64_t total_packed = 0;
        for (size_t i = 0; i < entries.size(); ++i) {
            const auto & entry = entries[i];
            ggml_tensor * tensor = ggml_get_tensor(tensor_ctx, entry.name);
            const int64_t tensor_id = gguf_find_tensor(metadata, entry.name);
            const uint64_t raw_size = gguf_get_tensor_size(metadata, tensor_id);
            const uint64_t raw_offset = gguf_get_data_offset(metadata) + gguf_get_tensor_offset(metadata, tensor_id);
            std::vector<uint8_t> raw(static_cast<size_t>(raw_size));
            std::vector<uint8_t> packed(static_cast<size_t>(entry.size));
            input.clear();
            input.seekg(static_cast<std::streamoff>(raw_offset));
            if (!input.read(reinterpret_cast<char *>(raw.data()), static_cast<std::streamsize>(raw.size())))
                throw std::runtime_error(std::string("failed reading tensor ") + entry.name);
            tensor->data = packed.data();
            htp_repack_tensor_tiled(tensor, raw.data(), raw.size());
            tensor->data = nullptr;
            out.seekp(static_cast<std::streamoff>(entry.offset));
            out.write(reinterpret_cast<const char *>(packed.data()), static_cast<std::streamsize>(packed.size()));
            if (!out) throw std::runtime_error(std::string("failed writing packed tensor ") + entry.name);
            total_packed += entry.size;
            std::cout << "[" << (i + 1) << "/" << entries.size() << "] " << entry.name
                      << " raw=" << raw_size << " tiled=" << entry.size << "\n";
        }
        out.flush();
        if (!out) throw std::runtime_error("failed flushing cache output");
        gguf_free(metadata);
        ggml_free(tensor_ctx);
        std::cout << "Wrote " << output << " tensors=" << entries.size()
                  << " payload=" << total_packed << " source_signature=" << signature << "\n";
        return 0;
    } catch (const std::exception & e) {
        std::cerr << "ERROR: " << e.what() << "\n";
        return 1;
    }
}
