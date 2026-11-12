#include "core.h"
#include <jni.h>
#include <openssl/mem.h>
#include <array>

namespace {
constexpr size_t kChunk = 16384;
template <size_t N> struct Buffer {
  std::array<uint8_t, N> bytes{};
  ~Buffer() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};
bool exact(JNIEnv *env, jbyteArray source, uint8_t *out, jsize size) {
  if (!source || env->GetArrayLength(source) != size) return false;
  env->GetByteArrayRegion(source, 0, size, reinterpret_cast<jbyte *>(out));
  return !env->ExceptionCheck();
}
template <typename Function> jint input(JNIEnv *env, jlong handle, jbyteArray data, Function f) {
  const jsize length = data ? env->GetArrayLength(data) : 0;
  if (length <= 0 || length > static_cast<jsize>(kChunk)) { d2fa_close(handle); return -1; }
  Buffer<kChunk> buffer;
  env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(buffer.bytes.data()));
  if (env->ExceptionCheck()) { d2fa_close(handle); return -1; }
  return f(handle, buffer.bytes.data(), static_cast<size_t>(length));
}
template <typename Function> jbyteArray output(JNIEnv *env, jlong handle, jint capacity, Function f) {
  if (capacity <= 0 || capacity > static_cast<jint>(kChunk)) { d2fa_close(handle); return nullptr; }
  Buffer<kChunk> buffer;
  const int count = f(handle, buffer.bytes.data(), static_cast<size_t>(capacity));
  if (count < 0) return nullptr;
  jbyteArray result = env->NewByteArray(count);
  if (!result) { d2fa_close(handle); return nullptr; }
  env->SetByteArrayRegion(result, 0, count, reinterpret_cast<const jbyte *>(buffer.bytes.data()));
  if (env->ExceptionCheck()) { d2fa_close(handle); return nullptr; }
  return result;
}
}  // namespace

extern "C" {
JNIEXPORT jlong JNICALL Java_org_desktop2fa_transfer_NativeCore_create(
    JNIEnv *env, jobject, jint role, jbyteArray sid, jbyteArray wid, jbyteArray code) {
  Buffer<16> s, w;
  Buffer<8> c;
  if (!exact(env, sid, s.bytes.data(), 16) || !exact(env, wid, w.bytes.data(), 16) ||
      !exact(env, code, c.bytes.data(), 8)) return 0;
  return static_cast<jlong>(d2fa_create(role, s.bytes.data(), 16, w.bytes.data(), 16, c.bytes.data(), 8));
}
JNIEXPORT jbyteArray JNICALL Java_org_desktop2fa_transfer_NativeCore_message(
    JNIEnv *env, jobject, jlong handle) { return output(env, handle, 32, d2fa_message); }
JNIEXPORT jint JNICALL Java_org_desktop2fa_transfer_NativeCore_finish(
    JNIEnv *env, jobject, jlong handle, jbyteArray peer) {
  Buffer<32> message;
  if (!exact(env, peer, message.bytes.data(), 32)) { d2fa_close(handle); return -1; }
  return d2fa_finish(handle, message.bytes.data(), 32);
}
JNIEXPORT jint JNICALL Java_org_desktop2fa_transfer_NativeCore_tlsStart(
    JNIEnv *, jobject, jlong handle) { return d2fa_tls_start(handle); }
JNIEXPORT jint JNICALL Java_org_desktop2fa_transfer_NativeCore_tlsStep(
    JNIEnv *, jobject, jlong handle) { return d2fa_tls_step(handle); }
JNIEXPORT jint JNICALL Java_org_desktop2fa_transfer_NativeCore_feed(
    JNIEnv *env, jobject, jlong handle, jbyteArray data) { return input(env, handle, data, d2fa_tls_feed); }
JNIEXPORT jbyteArray JNICALL Java_org_desktop2fa_transfer_NativeCore_drain(
    JNIEnv *env, jobject, jlong handle, jint capacity) { return output(env, handle, capacity, d2fa_tls_drain); }
JNIEXPORT jint JNICALL Java_org_desktop2fa_transfer_NativeCore_write(
    JNIEnv *env, jobject, jlong handle, jbyteArray data) { return input(env, handle, data, d2fa_tls_write); }
JNIEXPORT jbyteArray JNICALL Java_org_desktop2fa_transfer_NativeCore_read(
    JNIEnv *env, jobject, jlong handle, jint capacity) { return output(env, handle, capacity, d2fa_tls_read); }
JNIEXPORT jint JNICALL Java_org_desktop2fa_transfer_NativeCore_disconnect(
    JNIEnv *, jobject, jlong handle) { return d2fa_disconnect(handle); }
JNIEXPORT void JNICALL Java_org_desktop2fa_transfer_NativeCore_close(
    JNIEnv *, jobject, jlong handle) { d2fa_close(handle); }
#ifdef D2FA_TEST_API
JNIEXPORT jbyteArray JNICALL Java_org_desktop2fa_transfer_NativeCore_testKey(
    JNIEnv *env, jobject, jlong handle) { return output(env, handle, 64, d2fa_test_key); }
#endif
}
