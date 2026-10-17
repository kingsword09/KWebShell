#ifndef KWEB_PROCESSES_DIGEST_H_
#define KWEB_PROCESSES_DIGEST_H_

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace kwebshell::processes {

/**
 * The SHA-256 used to bind one packaged executable to its declared digest.
 * Self-contained so identity verification needs no extra dependency.
 */
class Sha256 {
 public:
  void Update(const uint8_t *data, size_t size) {
    bit_length_ += static_cast<uint64_t>(size) * 8u;
    while (size > 0) {
      const size_t take = std::min(size, sizeof(buffer_) - buffer_size_);
      std::memcpy(buffer_ + buffer_size_, data, take);
      buffer_size_ += take;
      data += take;
      size -= take;
      if (buffer_size_ == sizeof(buffer_)) {
        Transform(buffer_);
        buffer_size_ = 0;
      }
    }
  }

  void Finish(uint8_t digest[32]) {
    const uint64_t total_bits = bit_length_;
    uint8_t padding[72] = {};
    padding[0] = 0x80;
    const size_t padding_size = (buffer_size_ < 56) ? (56 - buffer_size_) : (120 - buffer_size_);
    Update(padding, padding_size);
    uint8_t length[8];
    for (int index = 0; index < 8; ++index) {
      length[index] = static_cast<uint8_t>(total_bits >> (56 - 8 * index));
    }
    Update(length, sizeof(length));
    for (int index = 0; index < 8; ++index) {
      digest[index * 4 + 0] = static_cast<uint8_t>(state_[index] >> 24);
      digest[index * 4 + 1] = static_cast<uint8_t>(state_[index] >> 16);
      digest[index * 4 + 2] = static_cast<uint8_t>(state_[index] >> 8);
      digest[index * 4 + 3] = static_cast<uint8_t>(state_[index]);
    }
  }

 private:
  static uint32_t Rotate(uint32_t value, int bits) { return (value >> bits) | (value << (32 - bits)); }

  void Transform(const uint8_t block[64]) {
    static const uint32_t k[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u,
        0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu,
        0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu,
        0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau, 0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
        0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu,
        0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
        0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u, 0x19a4c116u,
        0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u,
        0xc67178f2u};
    uint32_t w[64];
    for (int index = 0; index < 16; ++index) {
      w[index] = (static_cast<uint32_t>(block[index * 4]) << 24) |
                 (static_cast<uint32_t>(block[index * 4 + 1]) << 16) |
                 (static_cast<uint32_t>(block[index * 4 + 2]) << 8) |
                 static_cast<uint32_t>(block[index * 4 + 3]);
    }
    for (int index = 16; index < 64; ++index) {
      const uint32_t s0 = Rotate(w[index - 15], 7) ^ Rotate(w[index - 15], 18) ^ (w[index - 15] >> 3);
      const uint32_t s1 = Rotate(w[index - 2], 17) ^ Rotate(w[index - 2], 19) ^ (w[index - 2] >> 10);
      w[index] = w[index - 16] + s0 + w[index - 7] + s1;
    }
    uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
    uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
    for (int index = 0; index < 64; ++index) {
      const uint32_t s1 = Rotate(e, 6) ^ Rotate(e, 11) ^ Rotate(e, 25);
      const uint32_t ch = (e & f) ^ ((~e) & g);
      const uint32_t temp1 = h + s1 + ch + k[index] + w[index];
      const uint32_t s0 = Rotate(a, 2) ^ Rotate(a, 13) ^ Rotate(a, 22);
      const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
      const uint32_t temp2 = s0 + maj;
      h = g;
      g = f;
      f = e;
      e = d + temp1;
      d = c;
      c = b;
      b = a;
      a = temp1 + temp2;
    }
    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
    state_[4] += e;
    state_[5] += f;
    state_[6] += g;
    state_[7] += h;
  }

  uint32_t state_[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
  uint64_t bit_length_ = 0;
  uint8_t buffer_[64] = {};
  size_t buffer_size_ = 0;
};

/** The lowercase hex form of one digest. */
inline std::string ToHex(const uint8_t *bytes, size_t size) {
  static const char *digits = "0123456789abcdef";
  std::string output;
  output.reserve(size * 2);
  for (size_t index = 0; index < size; ++index) {
    output.push_back(digits[bytes[index] >> 4]);
    output.push_back(digits[bytes[index] & 0x0fu]);
  }
  return output;
}

/** The lowercase hex SHA-256 of one buffer. */
inline std::string Sha256Hex(const uint8_t *data, size_t size) {
  Sha256 hash;
  hash.Update(data, size);
  uint8_t digest[32];
  hash.Finish(digest);
  return ToHex(digest, sizeof(digest));
}

}  // namespace kwebshell::processes

#endif
