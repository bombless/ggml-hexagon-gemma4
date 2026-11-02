if (NOT DEFINED OUTPUT OR OUTPUT STREQUAL "" OR NOT DEFINED BACKEND OR BACKEND STREQUAL "")
    message(FATAL_ERROR "OUTPUT and BACKEND are required")
endif()

file(WRITE "${OUTPUT}"
    "backend=${BACKEND}\nupstream_commit=a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d\ndsp=HTP-v75\nabi=arm64-v8a\n")
