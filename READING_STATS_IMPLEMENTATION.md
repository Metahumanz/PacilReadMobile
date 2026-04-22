# 阅读时长统计实现说明

## 总体目标
- Android 端新增一套显式开启的阅读时长能力。
- 统计支持全局视角和单书视角。
- 数据既能保存在本地数据库，也能通过 WebDAV 做多设备累计同步。
- 后续 Win11 版本可以直接复用同一套书籍标识、统计聚合模型和远端 JSON 协议。

## 计时逻辑
- 记录开关由 `SettingsStore.reading_time_tracking_enabled` 控制，默认关闭。
- 进入阅读页并成功加载书籍后，阅读统计跟踪器会启动一个活跃窗口。
- 活跃窗口内的“活动信号”包括：
  - 页面点击和手势按下
  - 翻页成功、跳章成功、拖动进度条成功
  - 自动翻页推进
  - TTS 开始朗读新片段或跨页推进
- idle 规则：
  - 连续 60 秒没有新的活动信号，就把当前活跃窗口截止到 `lastActivity + 60s`
  - 后续再次发生交互时，开启新的活跃窗口，不会把中间空闲时间计入
- 持久化策略：
  - 跟踪器会按 60 秒 checkpoint
  - 阅读页 `onPause()` 时会强制 flush 一次
  - flush 后会做一次去抖的 WebDAV 上传

## 数据库设计

### books 表新增
- `reading_stats_key TEXT NOT NULL DEFAULT ''`

用途：
- 这是跨端统计时的稳定书籍身份。
- 新导入书籍时，按“规范化后的标题 + 作者”生成 SHA-256。
- 后续用户修改书名/作者时，不会改这个键，因此单书历史统计不会断档。

### reading_stats 表扩展
- 仍沿用原表，不新开第二张统计表。
- 新增字段：
  - `source_device_id`
  - `book_identity`
  - `book_title`
  - `book_author`
  - `updated_at`
- 仍保留旧字段：
  - `date`
  - `duration_seconds`
  - `char_count`

### 唯一约束
- 使用唯一索引：
  - `(source_device_id, date, book_identity)`

含义：
- 同一设备、同一天、同一本书只有一个统计桶。
- 本地写入时对这个桶做累加。
- 远端合并时按这个桶做幂等覆盖。

## 旧数据迁移
- 旧版 `reading_stats` 只有“按天总量”，没有设备和书籍维度。
- 升级后会把旧数据回填到保留桶：
  - `source_device_id='__legacy_device__'`
  - `book_identity='__legacy_total__'`
  - `book_title='历史阅读总时长'`
- 这样旧数据仍然能出现在“全局统计”的总量里。
- 同时，按书列表会明确排除 `__legacy_total__`，避免旧总量伪装成某一本书。

## 查询模型
- 全局统计：
  - 直接汇总指定日期范围内全部 `duration_seconds`
- 单书统计：
  - 按 `book_identity` 查询指定日期范围内的总时长
- 按书列表：
  - 以 `book_identity` 分组
  - 聚合 `SUM(duration_seconds)`
  - 取最新 `updated_at`
  - 再通过 `books.reading_stats_key` 反查本地书籍与封面

## WebDAV 同步设计

### 目录结构
- 阅读进度沿用原来的 `bookProgress/`
- 阅读时长新增独立目录：
  - `PacilRead/readingStats/`

### 文件粒度
- 每台设备一个文件：
  - `device-<deviceId>.json`

这样做的原因：
- 不同设备的统计天然隔离
- 上传是整机快照，不需要远端追加日志
- 合并时只要按 `(source_device_id, date, book_identity)` upsert 即可
- 重复下载/重复上传不会重复累计

### JSON 结构
```json
{
  "schemaVersion": 1,
  "deviceId": "device-uuid",
  "generatedAt": 1710000000000,
  "rows": [
    {
      "date": "2026-04-22",
      "sourceDeviceId": "device-uuid",
      "bookIdentity": "sha256...",
      "bookTitle": "示例书名",
      "bookAuthor": "示例作者",
      "durationSeconds": 1800,
      "charCount": 0,
      "updatedAt": 1710000000000
    }
  ]
}
```

### 合并规则
- 本地写入：
  - 对同一桶做增量累加
- 远端下载合并：
  - 如果本地没有该桶，直接插入
  - 如果本地已有该桶，且远端 `updatedAt >= local.updatedAt`，用远端整桶覆盖
- 最终“多端累计”的来源：
  - 查询阶段把不同 `source_device_id` 的同书同日数据直接求和

## 设置页与 UI 入口
- 设置页新增：
  - `记录阅读时长` 开关
  - `本日 / 本周 / 本年` 总览卡片
  - `查看详细统计` 按钮
- 关闭开关时：
  - 无历史数据：直接关闭
  - 有历史数据：弹窗提供 `只隐藏 / 清空历史 / 取消`
- `只隐藏`：
  - 关闭记录
  - 隐藏设置页总览和阅读页书名入口
  - 不删除本地和云端数据
- `清空历史`：
  - 删除本地 `reading_stats`
  - 尝试删除 WebDAV `readingStats/` 下的 JSON 文件
  - 远端删除失败时，不完成关闭动作

## 统计页
- `ReadingStatsActivity` 同时承担两种模式：
  - 无 `book_id`：全局统计
  - 有 `book_id`：单书统计
- 全局模式：
  - 展示当前周期总时长
  - 展示按书时长排行
  - 对于当前设备存在的书，可点击进入单书统计
- 单书模式：
  - 展示封面、书名、作者
  - 展示章节进度、最近阅读时间
  - 展示当前周期阅读时长

## 备份与恢复
- 完整数据库备份天然包含阅读统计。
- 精简数据库导出现在也包含：
  - `books.reading_stats_key`
  - `reading_stats`
- 增量恢复时，会把来源数据库里的 `reading_stats` 行合并进当前库，而不是简单丢弃。

## Win11 版本复用建议
- 直接复用 `reading_stats_key` 生成规则，保证跨端书籍身份一致。
- 继续使用“按设备 + 按日 + 按书”的聚合桶设计，不建议改成逐事件日志。
- 继续沿用 `device-<deviceId>.json` 的远端存储模型，Win11 只需要实现同样的：
  - 本地桶写入
  - JSON 快照上传
  - JSON 快照下载合并
- 如果桌面端要展示更细粒度的统计图表，也建议建立在现有日桶之上，避免破坏现有同步协议。
