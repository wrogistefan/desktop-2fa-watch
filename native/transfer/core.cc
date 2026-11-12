#include "core.h"
#include <openssl/bio.h>
#include <openssl/curve25519.h>
#include <openssl/evp.h>
#include <openssl/err.h>
#include <openssl/mem.h>
#include <openssl/nid.h>
#include <openssl/ssl.h>
#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr char kAlpn[] = "desktop2fa-transfer/1";
constexpr size_t kChunk = 16384, kQueue = 65536;
struct Secret {
  std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> bytes{};
  ~Secret() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};
struct Context {
  int role;
  bool dead = false, authenticated = false, was_authenticated = false;
  std::array<uint8_t, 16> sid{}, wid{};
  std::array<uint8_t, 32> message{};
  bssl::UniquePtr<SPAKE2_CTX> pake;
  bssl::UniquePtr<SSL_CREDENTIAL> credential;
  bssl::UniquePtr<SSL_CTX> tls_context;
  bssl::UniquePtr<SSL> tls;
  BIO *input = nullptr, *output = nullptr;  // Owned by tls.
#ifdef D2FA_TEST_API
  Secret test_key;
#endif
  int fail() {
    dead = true; authenticated = false; was_authenticated = false;
    pake.reset(); tls.reset(); tls_context.reset(); credential.reset();
    input = output = nullptr;
#ifdef D2FA_TEST_API
    OPENSSL_cleanse(test_key.bytes.data(), test_key.bytes.size());
#endif
    return -1;
  }
};
std::mutex mutex;
std::unordered_map<uint64_t, std::unique_ptr<Context>> contexts;
uint64_t next_handle = 1;

template <typename Function> int invoke(uint64_t handle, Function function) {
  try {
    std::lock_guard<std::mutex> lock(mutex);
    auto found = contexts.find(handle);
    if (found == contexts.end() || found->second->dead) return -1;
    try { return function(*found->second); }
    catch (...) { return found->second->fail(); }
  } catch (...) { return -1; }
}
std::vector<uint8_t> context_bytes(const char *prefix, const Context &ctx) {
  std::vector<uint8_t> result(prefix, prefix + std::strlen(prefix));
  result.insert(result.end(), ctx.sid.begin(), ctx.sid.end());
  result.insert(result.end(), ctx.wid.begin(), ctx.wid.end());
  return result;
}
std::string hex(const std::array<uint8_t, 16> &bytes) {
  const char *alphabet = "0123456789abcdef";
  std::string out;
  for (uint8_t byte : bytes) { out += alphabet[byte >> 4]; out += alphabet[byte & 15]; }
  return out;
}
int select_alpn(SSL *, const uint8_t **out, uint8_t *out_len,
                const uint8_t *in, unsigned in_len, void *) {
  constexpr size_t length = sizeof(kAlpn) - 1;
  if (in_len != length + 1 || in[0] != length || std::memcmp(in + 1, kAlpn, length))
    return SSL_TLSEXT_ERR_ALERT_FATAL;
  *out = reinterpret_cast<const uint8_t *>(kAlpn);
  *out_len = static_cast<uint8_t>(length);
  return SSL_TLSEXT_ERR_OK;
}
bool profile(const Context &ctx) {
  const uint8_t *alpn = nullptr;
  unsigned alpn_len = 0;
  SSL_get0_alpn_selected(ctx.tls.get(), &alpn, &alpn_len);
  const SSL_CIPHER *cipher = SSL_get_current_cipher(ctx.tls.get());
  if (!cipher) return false;
  const uint16_t id = SSL_CIPHER_get_protocol_id(cipher);
  return SSL_version(ctx.tls.get()) == TLS1_3_VERSION &&
      alpn_len == sizeof(kAlpn) - 1 && !std::memcmp(alpn, kAlpn, alpn_len) &&
      (id == 0x1301 || id == 0x1302 || id == 0x1303) &&
      SSL_get_negotiated_group(ctx.tls.get()) == NID_X9_62_prime256v1 &&
      !SSL_in_early_data(ctx.tls.get()) && !SSL_session_reused(ctx.tls.get());
}
int io_result(Context &ctx, int result) {
  if (result > 0) return result;
  const int error = SSL_get_error(ctx.tls.get(), result);
  if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE) return 0;
  return ctx.fail();
}
}  // namespace

uint64_t d2fa_create(int role, const uint8_t *sid, size_t sid_len,
                    const uint8_t *wid, size_t wid_len, const uint8_t *code, size_t code_len) {
  if ((role != 0 && role != 1) || !sid || sid_len != 16 || !wid || wid_len != 16 ||
      !code || code_len != 8) return 0;
  for (size_t i = 0; i < 8; ++i) if (code[i] < '0' || code[i] > '9') return 0;
  try {
    std::lock_guard<std::mutex> lock(mutex);
    if (contexts.size() >= 64 || next_handle >= static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) return 0;
    auto ctx = std::make_unique<Context>();
    ctx->role = role;
    std::memcpy(ctx->sid.data(), sid, 16); std::memcpy(ctx->wid.data(), wid, 16);
    const auto a = context_bytes("desktop2fa-transfer/1|D2FA-SPAKE2-BSSL1|watch|", *ctx);
    const auto b = context_bytes("desktop2fa-transfer/1|D2FA-SPAKE2-BSSL1|desktop|", *ctx);
    const auto &mine = role == 0 ? a : b;
    const auto &theirs = role == 0 ? b : a;
    ctx->pake.reset(SPAKE2_CTX_new(role == 0 ? spake2_role_alice : spake2_role_bob,
        mine.data(), mine.size(), theirs.data(), theirs.size()));
    size_t length = 0;
    if (!ctx->pake || !SPAKE2_generate_msg(ctx->pake.get(), ctx->message.data(), &length,
        ctx->message.size(), code, code_len) || length != 32) return 0;
    const uint64_t handle = next_handle++;
    contexts.emplace(handle, std::move(ctx));
    return handle;
  } catch (...) { return 0; }
}
int d2fa_message(uint64_t handle, uint8_t *out, size_t capacity) {
  return invoke(handle, [&](Context &ctx) {
    if (!out || capacity < 32 || !ctx.pake) return ctx.fail();
    std::memcpy(out, ctx.message.data(), 32); return 32;
  });
}
int d2fa_finish(uint64_t handle, const uint8_t *peer, size_t length) {
  return invoke(handle, [&](Context &ctx) {
    if (!peer || length != 32 || !ctx.pake || ctx.credential) return ctx.fail();
    Secret key;
    size_t key_len = 0;
    const int ok = SPAKE2_process_msg(ctx.pake.get(), key.bytes.data(), &key_len,
        key.bytes.size(), peer, length);
    ctx.pake.reset();  // Single use, including failure.
    if (!ok || key_len != 64) return ctx.fail();
    auto context = context_bytes(
        "desktop2fa-transfer/1|D2FA-SPAKE2-BSSL1|watch-client|desktop-server|", ctx);
    const uint8_t *a = ctx.role == 0 ? ctx.message.data() : peer;
    const uint8_t *b = ctx.role == 0 ? peer : ctx.message.data();
    context.insert(context.end(), a, a + 32); context.insert(context.end(), b, b + 32);
    const std::string identity = "d2fa-transfer/1/" + hex(ctx.sid) + "/" + hex(ctx.wid);
    ctx.credential.reset(SSL_CREDENTIAL_new_pre_shared_key(key.bytes.data(), key_len,
        reinterpret_cast<const uint8_t *>(identity.data()), identity.size(), EVP_sha256(),
        context.data(), context.size()));
    if (!ctx.credential) return ctx.fail();
#ifdef D2FA_TEST_API
    ctx.test_key.bytes = key.bytes;
#endif
    return 1;  // Key derivation only; NOT authentication.
  });
}
int d2fa_tls_start(uint64_t handle) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.credential || ctx.tls) return ctx.fail();
    ctx.tls_context.reset(SSL_CTX_new(TLS_method()));
    if (!ctx.tls_context || !SSL_CTX_set_min_proto_version(ctx.tls_context.get(), TLS1_3_VERSION) ||
        !SSL_CTX_set_max_proto_version(ctx.tls_context.get(), TLS1_3_VERSION) ||
        !SSL_CTX_set_num_tickets(ctx.tls_context.get(), 0)) return ctx.fail();
    SSL_CTX_set_options(ctx.tls_context.get(), SSL_OP_NO_TICKET);
    SSL_CTX_set_session_cache_mode(ctx.tls_context.get(), SSL_SESS_CACHE_OFF);
    if (ctx.role == 1) SSL_CTX_set_alpn_select_cb(ctx.tls_context.get(), select_alpn, nullptr);
    ctx.tls.reset(SSL_new(ctx.tls_context.get()));
    const uint16_t group = SSL_GROUP_SECP256R1;
    if (!ctx.tls || !SSL_add1_credential(ctx.tls.get(), ctx.credential.get()) ||
        !SSL_set1_group_ids(ctx.tls.get(), &group, 1)) return ctx.fail();
    // No certificate-related or verify-mode API: exactly one imported PSK, no fallback.
    SSL_set_early_data_enabled(ctx.tls.get(), 0);
    std::array<uint8_t, sizeof(kAlpn)> alpn{};
    alpn[0] = sizeof(kAlpn) - 1;
    std::memcpy(alpn.data() + 1, kAlpn, sizeof(kAlpn) - 1);
    if (ctx.role == 0 && SSL_set_alpn_protos(ctx.tls.get(), alpn.data(), alpn.size()) != 0)
      return ctx.fail();
    bssl::UniquePtr<BIO> input(BIO_new(BIO_s_mem())), output(BIO_new(BIO_s_mem()));
    if (!input || !output) return ctx.fail();
    BIO_set_mem_eof_return(input.get(), -1);
    ctx.input = input.get(); ctx.output = output.get();
    SSL_set_bio(ctx.tls.get(), input.release(), output.release());
    if (ctx.role == 0) SSL_set_connect_state(ctx.tls.get()); else SSL_set_accept_state(ctx.tls.get());
    ctx.authenticated = false;
    return 1;
  });
}
int d2fa_tls_step(uint64_t handle) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.tls) return ctx.fail();
    if (ctx.authenticated) return 1;
    ERR_clear_error();
    const int result = ctx.role == 0 ? SSL_connect(ctx.tls.get()) : SSL_accept(ctx.tls.get());
    if (result != 1) return io_result(ctx, result);
    if (!profile(ctx)) return ctx.fail();
    ctx.authenticated = ctx.was_authenticated = true;
    return 1;
  });
}
int d2fa_tls_feed(uint64_t handle, const uint8_t *data, size_t length) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.tls || !data || length == 0 || length > kChunk ||
        BIO_pending(ctx.input) + length > kQueue) return ctx.fail();
    const int written = BIO_write(ctx.input, data, static_cast<int>(length));
    return written == static_cast<int>(length) ? written : ctx.fail();
  });
}
int d2fa_tls_drain(uint64_t handle, uint8_t *out, size_t capacity) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.tls || !out || capacity == 0 || capacity > kChunk || BIO_pending(ctx.output) > kQueue)
      return ctx.fail();
    if (!BIO_pending(ctx.output)) return 0;
    const int count = BIO_read(ctx.output, out, static_cast<int>(capacity));
    return count > 0 ? count : ctx.fail();
  });
}
int d2fa_tls_write(uint64_t handle, const uint8_t *data, size_t length) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.tls || !ctx.authenticated || !data || length == 0 || length > kChunk ||
        BIO_pending(ctx.output) > kQueue - kChunk - 256) return ctx.fail();
    ERR_clear_error();
    return io_result(ctx, SSL_write(ctx.tls.get(), data, static_cast<int>(length)));
  });
}
int d2fa_tls_read(uint64_t handle, uint8_t *out, size_t capacity) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.tls || !ctx.authenticated || !out || capacity == 0 || capacity > kChunk) return ctx.fail();
    ERR_clear_error();
    return io_result(ctx, SSL_read(ctx.tls.get(), out, static_cast<int>(capacity)));
  });
}
int d2fa_disconnect(uint64_t handle) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.was_authenticated) return ctx.fail();
    ctx.tls.reset(); ctx.tls_context.reset(); ctx.input = ctx.output = nullptr;
    ctx.authenticated = false;
    return 1;
  });
}
void d2fa_close(uint64_t handle) {
  try { std::lock_guard<std::mutex> lock(mutex); contexts.erase(handle); } catch (...) {}
}
#ifdef D2FA_TEST_API
int d2fa_test_key(uint64_t handle, uint8_t *out, size_t capacity) {
  return invoke(handle, [&](Context &ctx) {
    if (!ctx.credential || !out || capacity != 64) return -1;
    std::memcpy(out, ctx.test_key.bytes.data(), 64); return 64;
  });
}
#endif
