#ifndef D2FA_TRANSFER_CORE_H
#define D2FA_TRANSFER_CORE_H
#include <stddef.h>
#include <stdint.h>
#if defined(_WIN32)
#if defined(D2FA_TRANSFER_BUILD)
#define D2FA_API __declspec(dllexport)
#else
#define D2FA_API __declspec(dllimport)
#endif
#else
#define D2FA_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
// Experimental crypto boundary, not a complete receiver/session implementation.
// Handles are process-local, single-owner values. All functions are serialized.
// Role 0 = Watch/Alice/client; 1 = Desktop/Bob/server. Inputs are exact raw bytes.
// No function logs, persists credentials, exposes production K, or uses SSLKEYLOGFILE.
D2FA_API uint64_t d2fa_create(int role, const uint8_t *sid, size_t sid_len,
    const uint8_t *wid, size_t wid_len, const uint8_t *code, size_t code_len);
D2FA_API int d2fa_message(uint64_t handle, uint8_t *out, size_t capacity);
D2FA_API int d2fa_finish(uint64_t handle, const uint8_t *peer, size_t length);
D2FA_API int d2fa_tls_start(uint64_t handle);
// 1 = authenticated full handshake with checked profile; 0 = needs more I/O; -1 = failed.
D2FA_API int d2fa_tls_step(uint64_t handle);
// Feed/drain raw TLS bytes. Each call is bounded to 16384 bytes, queues to 65536.
D2FA_API int d2fa_tls_feed(uint64_t handle, const uint8_t *data, size_t length);
D2FA_API int d2fa_tls_drain(uint64_t handle, uint8_t *out, size_t capacity);
// Application I/O is rejected before authenticated handshake completion.
D2FA_API int d2fa_tls_write(uint64_t handle, const uint8_t *data, size_t length);
D2FA_API int d2fa_tls_read(uint64_t handle, uint8_t *out, size_t capacity);
// Only after authentication: retain imported credential, discard old TLS connection.
// Application must independently enforce lifetime, locking and reconnect limits.
D2FA_API int d2fa_disconnect(uint64_t handle);
D2FA_API void d2fa_close(uint64_t handle);
#ifdef D2FA_TEST_API
// Compiled only into explicitly non-shipping test builds. Never log its output.
D2FA_API int d2fa_test_key(uint64_t handle, uint8_t *out, size_t capacity);
#endif
#ifdef __cplusplus
}
#endif
#endif
