# Dataflow / Channel

[![Maven Central](https://img.shields.io/maven-central/v/pub.lighting/threadforge-core?label=Maven%20Central)](https://search.maven.org/artifact/pub.lighting/threadforge-core)
[![Java](https://img.shields.io/badge/Java-8%2B-007396)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://github.com/wuuJiawei/ThreadForge/blob/main/LICENSE)


`Channel<T>` 是有界阻塞通道，适合在多个任务间进行背压传递。

- 类型：`public final class Channel<T> implements Iterable<T>`
- 线程安全：支持多生产者、多消费者

## 创建

### `static <T> Channel<T> bounded(int capacity)`

创建固定容量通道。

- 参数：`capacity > 0`
- 异常：`IllegalArgumentException`（容量非法）

## 发送与接收

### `void send(T value)`

发送一个元素。

- 缓冲满时阻塞
- 通道关闭后抛 `ChannelClosedException`

### `T receive()`

接收一个元素。

- 缓冲空且未关闭时阻塞
- 通道已关闭且已耗尽时抛 `ChannelClosedException`

### `void close()`

关闭通道。

- 不会清空已入队元素
- 唤醒所有等待中的发送者/接收者

### `boolean isClosed()`

通道是否已关闭。

## 迭代语义

`iterator()` 会持续调用 `receive()`，直到抛出 `ChannelClosedException` 结束迭代。

```java
Channel<Integer> ch = Channel.bounded(16);

scope.submit(() -> {
    for (int i = 0; i < 5; i++) {
        ch.send(i);
    }
    ch.close();
    return null;
});

Task<Integer> sum = scope.submit(() -> {
    int total = 0;
    for (Integer v : ch) {
        total += v;
    }
    return total;
});
```
