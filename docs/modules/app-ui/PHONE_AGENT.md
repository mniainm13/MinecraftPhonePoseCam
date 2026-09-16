# 手机 UI Agent 快速上手

你是 **C1 App UI** 的 owner。领头 Agent 负责接口与集成。

## 你只能改

```
android/app/src/main/java/dev/phonecam/app/ui/**
android/app/src/main/res/layout/**
android/app/src/main/res/drawable/**
android/app/src/main/res/values/**
```

桥接口：

```
android/.../ui/bridge/ViewfinderUiBridge.kt
android/.../ui/bridge/UiHost.kt
android/.../ui/bridge/ViewfinderUiState.kt   // 在 ViewfinderUiBridge.kt 内
```

## 你不能改

- `net/`、`stream/`、`sensor/`
- `mod/`、`stream_host/`、`protocol/`
- 直接 new UDP/TCP/ARCore/MediaCodec

## 标准写法

```kotlin
// 在自定义 View / Fragment / 可选 Activity 里
val bridge = (context as? ViewfinderUiBridge) ?: return
bridge.setBlurEnabled(true)
val s = bridge.currentState()
statusLabel.text = s.statusText
```

`MainActivity` 已 `implements ViewfinderUiBridge`。  
新控件优先构造注入 bridge；拿不到再从 Activity 强转。

## 交付要求

1. 提交信息：`app-ui: ...`
2. 完成后更新 `docs/modules/app-ui/HANDOFF.md`
3. 需要新状态字段 → **不要**改 bridge 协议语义，找领头加字段
4. 合并前 `gradle assembleDebug` 通过

## 当前 UI 资产

- `activity_main.xml` — 主壳
- `FrostBlurView` / `FrostBlurRenderer` — 磨砂
- `ZoomPillBar` / `ModePillBar` — 底栏
- 设置浮层 + 磨砂浮动卡片
