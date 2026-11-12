#include "core.h"
#include <openssl/mem.h>
#include <openssl/rand.h>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {
void check(bool condition) {
  if (!condition) { std::fputs("Native profile test failed (details redacted)\n", stderr); std::abort(); }
}
struct Pair {
  uint64_t a, b;
  Pair(const char *a_code = "00001234", const char *b_code = "00001234", int bob_role = 1,
       bool changed_wid = false) {
    std::array<uint8_t, 16> sid{}, wid{};
    check(RAND_bytes(sid.data(), sid.size()) == 1);
    check(RAND_bytes(wid.data(), wid.size()) == 1);
    a = d2fa_create(0, sid.data(), 16, wid.data(), 16,
        reinterpret_cast<const uint8_t *>(a_code), std::strlen(a_code));
    if (changed_wid) wid[0] ^= 1;
    b = d2fa_create(bob_role, sid.data(), 16, wid.data(), 16,
        reinterpret_cast<const uint8_t *>(b_code), std::strlen(b_code));
    check(a && b);
  }
  ~Pair() { d2fa_close(a); d2fa_close(b); }
  bool exchange() {
    std::array<uint8_t, 32> am{}, bm{};
    check(d2fa_message(a, am.data(), am.size()) == 32);
    check(d2fa_message(b, bm.data(), bm.size()) == 32);
    return d2fa_finish(a, bm.data(), bm.size()) == 1 && d2fa_finish(b, am.data(), am.size()) == 1;
  }
  bool keys_match() {
    std::array<uint8_t, 64> ka{}, kb{};
    check(d2fa_test_key(a, ka.data(), ka.size()) == 64);
    check(d2fa_test_key(b, kb.data(), kb.size()) == 64);
    const bool equal = CRYPTO_memcmp(ka.data(), kb.data(), 64) == 0;
    OPENSSL_cleanse(ka.data(), ka.size()); OPENSSL_cleanse(kb.data(), kb.size());
    return equal;
  }
};
bool move(uint64_t from, uint64_t to, size_t fragmentation = 16384) {
  std::array<uint8_t, 16384> wire{};
  for (;;) {
    const int n = d2fa_tls_drain(from, wire.data(), fragmentation);
    if (n < 0) return false;
    if (!n) return true;
    if (d2fa_tls_feed(to, wire.data(), static_cast<size_t>(n)) != n) return false;
  }
}
bool handshake(Pair &pair, size_t fragmentation = 16384) {
  if (d2fa_tls_start(pair.a) != 1 || d2fa_tls_start(pair.b) != 1) return false;
  for (int iteration = 0; iteration < 100; ++iteration) {
    const int a = d2fa_tls_step(pair.a);
    if (a < 0 || !move(pair.a, pair.b, fragmentation)) return false;
    const int b = d2fa_tls_step(pair.b);
    if (b < 0 || !move(pair.b, pair.a, fragmentation)) return false;
    if (a == 1 && b == 1) return true;
  }
  return false;
}
void marker(Pair &pair) {
  const uint8_t marker[] = "D2FA synthetic nonsecret marker";
  check(d2fa_tls_write(pair.b, marker, sizeof(marker)) == sizeof(marker));
  check(move(pair.b, pair.a, 1));
  std::array<uint8_t, 128> result{};
  check(d2fa_tls_read(pair.a, result.data(), result.size()) == sizeof(marker));
  check(!std::memcmp(marker, result.data(), sizeof(marker)));
}
}  // namespace
int main() {
  std::array<uint8_t, 16> ids{};
  const uint8_t invalid_code[] = "1234567";
  check(!d2fa_create(0, ids.data(), 16, ids.data(), 16, invalid_code, 7));
  check(!d2fa_create(0, ids.data(), 16, ids.data(), 16,
      reinterpret_cast<const uint8_t *>("1234 567"), 8));
  check(d2fa_tls_step(0) == -1);
  {
    Pair pair;
    check(pair.exchange());
    // PAKE success must never authorize application data before TLS.
    const uint8_t marker[] = "synthetic";
    check(d2fa_tls_write(pair.a, marker, sizeof(marker)) == -1);
    check(d2fa_tls_start(pair.a) == -1);
  }
  for (int i = 0; i < 1000; ++i) {
    Pair pair;
    check(pair.exchange()); check(pair.keys_match());
    check(handshake(pair, i == 0 ? 1 : 16384)); marker(pair);
    if (i == 0) {
      check(d2fa_disconnect(pair.a) == 1); check(d2fa_disconnect(pair.b) == 1);
      check(handshake(pair)); marker(pair);
    }
  }
  {
    Pair wrong_code("00001234", "00001235");
    check(wrong_code.exchange()); check(!wrong_code.keys_match()); check(!handshake(wrong_code));
  }
  {
    Pair same_roles("00001234", "00001234", 0);
    check(!same_roles.exchange() || !handshake(same_roles));
  }
  {
    Pair changed_context("00001234", "00001234", 1, true);
    check(!changed_context.exchange() || !handshake(changed_context));
  }
  std::puts("PASS: 1000 fresh native exchanges, full-key equality, TLS marker, reconnect and negative cases");
}
