# Temperature and fan interfaces / 温度与风扇 / 溫度與風扇 / 温度とファン

## 简体中文

本记录来自 V1.1.8-20261027-021 固件静态分析，没有开启设备 SSH，也没有改动风扇设置。

- CLI 读取命令：`show fan`；Linux shell 读取路径：`/proc/fan`。
- 字段：`temperature_c`（摄氏温度）、`pwm_level`（PWM 目标档位）。PWM 档位不是实测 RPM，过渡过程中也不等于瞬时占空比。
- 驱动 `ski.ko` 以 `0444` 创建 proc 节点，提供读取操作，没有写回调。CLI 命令读取该节点。
- 风扇由内核温控逻辑自动调节。该固件代码的目标档位对应：低于 40°C 为 0，40–44°C 为 5，45–54°C 为 8，55°C 及以上为 10，并带有平滑变化逻辑。这是静态推导，不是当前温度/转速的实机测量。
- 没有找到网页手动调速 HTTP 接口。`fan_control_set` 是内核函数名，不是能直接从 App 调用的命令。
- 网页 `diag_optical` / `diag_opticalIntf` 的 `temp` 是 SFP 光模块温度，不能当作主板/CPU 温度。

若管理 CLI 尚未启用，需要由设备管理员另行决定是否开放；本应用不会为了读取温度而自动开启新的管理服务。

## English

Static analysis of V1.1.8-20261027-021 found CLI `show fan`, reading `/proc/fan`. Fields are `temperature_c` and `pwm_level`. PWM is a target level, not measured RPM or necessarily the instantaneous duty during transitions.

The kernel driver creates the proc node with mode `0444` and no write callback. Automatic thermal control targets levels 0 below 40°C, 5 at 40–44°C, 8 at 45–54°C, and 10 at 55°C or above, with gradual transitions. These are firmware findings, not current device measurements. No manual-speed HTTP endpoint was found. `fan_control_set` is an internal kernel function, not an App-callable command.

`diag_optical` / `diag_opticalIntf` report SFP module temperature, not board/CPU temperature. No SSH service or fan setting was changed during analysis; this app does not automatically enable management services.

## 繁體中文

V1.1.8-20261027-021 靜態分析確認 CLI `show fan` 讀取唯讀 `/proc/fan`，欄位為 `temperature_c` 與 `pwm_level`。PWM 是目標檔位，不是實測 RPM 或過渡期間的即時占空比。節點權限為 `0444`，沒有寫入回呼。

內核自動溫控目標：低於 40°C 為 0、40–44°C 為 5、45–54°C 為 8、55°C 以上為 10，並平滑調整。這是韌體分析，不是目前讀數。尚未找到網頁手動調速介面；`fan_control_set` 是內核函式。光學診斷的 `temp` 為 SFP 模組溫度。本次未開啟 SSH 或變更風扇，App 亦不會自動開啟管理服務。

## 日本語

V1.1.8-20261027-021 の静的解析で、CLI `show fan` が読み取り専用 `/proc/fan` を参照することを確認しました。フィールドは `temperature_c` と `pwm_level` です。PWM は目標レベルであり、実測 RPM や遷移中の瞬間的デューティ比ではありません。ノードは `0444` で作成され、書き込みコールバックはありません。

自動温度制御の目標値は、40°C 未満で 0、40–44°C で 5、45–54°C で 8、55°C 以上で 10 となり、段階的に変化します。これはファームウェア解析結果で、現在の測定値ではありません。手動速度設定の HTTP API は見つかっていません。`fan_control_set` は内部カーネル関数です。光学診断の `temp` は SFP モジュールの温度です。SSH の有効化やファン設定の変更は行っておらず、アプリが管理サービスを自動で有効にすることもありません。
