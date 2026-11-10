# Validation / 验证 / 驗證 / 検証

Version: **1.2.0**

| Check | Result |
| --- | --- |
| Android/JVM tests | 34 passed, 1 optional live test skipped, 0 failures |
| Web console transport | 21 assertions passed |
| Release build | Passed |
| Android Lint | 0 errors, 9 warnings |
| Miku primary color | #39C5BB |
| Primary-button text contrast | At least 4.5:1 |

简体中文：本次发布构建未提供真实设备凭据，因此可选局域网集成测试跳过。此前已在 V1.1.8-20261027-021 验证状态读取、145 个菜单页面 HTTP 访问和运行配置导出。恢复与刷机使用模拟设备验证，复杂网页交互仍需实体 Android 测试。

English: The release build omits real device credentials, so the optional LAN integration test is skipped. Earlier live validation covered status, 145 menu-page HTTP responses and configuration export on V1.1.8-20261027-021. Restore/flashing use mocked tests; complex web interactions still need physical Android validation.

繁體中文：發布建置未提供實機憑據，選用的區域網路整合測試跳過。先前已驗證狀態、145 個選單頁面與設定匯出。還原／刷機採模擬測試，複雜網頁互動仍需實體 Android 驗證。

日本語：公開ビルドには実機の認証情報を渡していないため、任意の LAN 統合テストをスキップしています。以前の実機確認では状態、145 ページ、設定のエクスポートを検証しました。復元・書き込みは模擬テストであり、複雑な Web 操作には実際の Android で追加確認が必要です。
