#pragma once
#include <utility>

namespace sifucraft::render {
template<class T> class Com {
public:
    Com() = default;
    ~Com() { reset(); }
    Com(const Com&) = delete;
    Com& operator=(const Com&) = delete;
    Com(Com&& other) noexcept : value_(std::exchange(other.value_, nullptr)) {}
    Com& operator=(Com&& other) noexcept { reset(); value_ = std::exchange(other.value_, nullptr); return *this; }
    T* get() const { return value_; }
    T* operator->() const { return value_; }
    explicit operator bool() const { return value_ != nullptr; }
    T** put() { reset(); return &value_; }
    void reset(T* value = nullptr) { if (value_) value_->Release(); value_ = value; }
private:
    T* value_ = nullptr;
};
}
