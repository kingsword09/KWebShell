#ifndef KWEBSHELL_NATIVE_PENDING_WAITERS_H_
#define KWEBSHELL_NATIVE_PENDING_WAITERS_H_

#include <algorithm>
#include <memory>
#include <vector>

namespace kwebshell {

template <typename T> class PendingWaiters final {
public:
  void Add(const std::shared_ptr<T> &waiter) { waiters_.emplace_back(waiter); }

  void Remove(const T *waiter) {
    waiters_.erase(
        std::remove_if(waiters_.begin(), waiters_.end(),
                       [waiter](const std::weak_ptr<T> &candidate) {
                         const auto live = candidate.lock();
                         return !live || live.get() == waiter;
                       }),
        waiters_.end());
  }

  std::vector<std::shared_ptr<T>> TakeLive() {
    std::vector<std::shared_ptr<T>> live;
    live.reserve(waiters_.size());
    for (const auto &candidate : waiters_) {
      if (auto waiter = candidate.lock()) {
        live.push_back(std::move(waiter));
      }
    }
    waiters_.clear();
    return live;
  }

  bool empty() const { return waiters_.empty(); }

private:
  std::vector<std::weak_ptr<T>> waiters_;
};

} // namespace kwebshell

#endif
