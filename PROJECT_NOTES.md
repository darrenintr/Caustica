# Caustica 專案注意事項

> 本文件整理開發、建置、安裝與使用 Caustica 時最需要留意的事項。
> 如本文件與程式碼或實際建置結果不一致，請以程式碼與最新建置結果為準。

## 1. 專案定位

- Caustica 是 Minecraft `26.2` 的 Fabric 客戶端模組，使用 Vulkan 硬體光線追蹤取代原版世界渲染。
- 專案目前仍屬實驗性質，可能遇到畫面錯誤、效能波動、相容性問題或功能未完成的情況。
- 模組為 client-side，伺服器端不需要安裝 Caustica。
- 目前專案版本可在 [`gradle.properties`](gradle.properties) 的 `mod_version` 查閱；不要只依檔名或舊報告判斷版本。

## 2. 執行環境要求

### 必要條件

- 使用 Fabric Loader、Fabric API，並以 Minecraft `26.2` 啟動。
- 啟用 Minecraft 的 Vulkan 圖形後端；如果遊戲崩潰後退回 OpenGL，重新啟動前要再次啟用 Vulkan。
- GPU 與驅動程式必須支援 Vulkan Ray Tracing。
- 建議使用與 GPU 相符的最新穩定 Vulkan 驅動程式，並先確認其他 Vulkan 程式能正常執行。

### 額外功能條件

| 功能 | 注意事項 |
| --- | --- |
| DLSS / DLSS Ray Reconstruction | 需要相容的 NVIDIA RTX GPU、驅動程式及可用的 NGX 元件。 |
| DLSS Frame Generation | 實驗性功能；需要支援的 NVIDIA 硬體與正確的原生元件。 |
| FSR / FFX | 需要隨平台提供的對應原生庫；未成功載入時應回退到可用的後端。 |
| XeSS | 依 GPU、驅動程式與原生庫支援程度而定，不能假設所有 Intel 或非 Intel 硬體都可用。 |
| HDR | 需要 HDR 顯示器、作業系統 HDR 模式及可用的 HDR swapchain。Linux 通常還需要原生 Wayland 工作階段。 |
| LabPBR 材質 | 建議搭配 LabPBR resource pack，例如 README 提到的 SPBR，以取得較完整的材質效果。 |

## 3. 建置注意事項

### Windows

1. 安裝 Java 21 JDK，並確認 `java -version` 可正常執行。
2. 安裝 Vulkan SDK，並確保以下工具可被找到：
   - `glslangValidator`
   - `spirv-val`
3. 在專案根目錄執行 `gradlew.bat build`，或依 [`BUILD_GUIDE.md`](BUILD_GUIDE.md) 使用 Windows 建置腳本。
4. 建置產物通常位於 `build/libs/`；安裝前請確認使用的是本次建置產生的 JAR。

建置流程會從 `shaders/` 編譯 shader 並執行 SPIR-V 驗證。不要只修改或複製舊的 `.spv` 檔案來代替重新建置，否則可能造成 shader 與原始碼不同步。

### Linux / CachyOS

- Linux 建置與執行方式請先閱讀 [`scripts/cachyos/README.md`](scripts/cachyos/README.md)。
- `scripts/cachyos/install.sh` 可能會安裝系統依賴、寫入系統設定或需要 `sudo`；執行前應先閱讀腳本內容並確認變更範圍。
- Linux JAR 必須包含正確的 `linux-x64` 原生庫，不能把 Windows DLL 當作 Linux 執行檔使用。
- HDR 依賴 Wayland 顯示路徑；X11/XWayland 通常無法提供所需的 HDR10/PQ 格式。

### 建置資源與網路

- 首次建置可能需要下載 Gradle、Fabric、Minecraft、Vulkan 及原生 SDK 依賴。
- 依賴下載失敗時，先檢查網路、代理伺服器與 Gradle 快取，再考慮使用 `--refresh-dependencies`。
- 不要把個人 SDK 路徑、代理設定、授權檔或下載的私有套件提交到 Git。

## 4. 設定檔注意事項

- 遊戲實際使用的設定檔是 `config/caustica.toml`；範例及 Windows 優化設定可參考 [`config/caustica-windows-optimized.toml`](config/caustica-windows-optimized.toml)。
- 修改設定前先備份原有 `config/caustica.toml`。範例設定不應直接覆蓋使用者已調整的設定。
- `denoise.mode`、`upscaler.mode`、`framegen.mode` 應根據 GPU 與已載入的原生庫選擇；`auto` 不是所有硬體情況下的保證結果。
- 調高 `spp`、`max-bounces` 或 temporal 累積品質通常會增加 GPU 負載與延遲；排查效能問題時先降低這些項目。
- 啟用 HDR 後通常需要重新啟動遊戲；若畫面異常，先關閉 HDR 並確認 SDR 路徑正常。
- Frame Generation、DLSS 相關設定屬實驗性功能；遇到輸入延遲、鬼影、閃爍或崩潰時，先停用 Frame Generation，再單獨測試 upscaler 與 denoiser。

## 5. 相容性與執行時風險

- Caustica 接管世界渲染，因此會與修改世界渲染、shader pipeline、後處理或 Vulkan backend 的模組產生衝突。
- UI、聊天、按鍵或其他不改動世界渲染的模組通常較容易相容，但仍應逐一測試。
- 任何效能比較都應固定 Minecraft 版本、世界、resource pack、解析度、GPU 驅動程式與設定；不要直接把單次測試結果當成普遍提升。
- 啟用 `debug-view`、`frame-stats` 或 `debug-overlay` 只應用於排查問題，完成測試後恢復關閉，以免影響效能與畫面判斷。
- 發生崩潰時，先記錄使用的 GPU、驅動程式、作業系統、啟動參數、設定檔、模組列表及最新日誌，再嘗試最小化配置重現。

## 6. 建議的排查順序

1. 確認啟動的是 Fabric + Minecraft `26.2`，且 Vulkan backend 已啟用。
2. 確認 GPU 驅動程式支援 Vulkan Ray Tracing，並沒有混用錯誤平台的原生庫。
3. 將 `config/caustica.toml` 備份後，以最小設定啟動；先關閉 HDR、Frame Generation 及非必要的實驗性功能。
4. 從遊戲日誌確認 denoiser、upscaler 與原生庫載入結果。
5. 若問題只在特定模組或 resource pack 出現，停用該項目並進行 A/B 測試。
6. 建置問題則先執行 `java -version`，再確認 Vulkan SDK 工具，最後才清理 Gradle 快取或使用 `gradlew.bat clean build`。

## 7. 開發與提交規則

- Shader 原始碼放在 `shaders/`；Java/Kotlin 與資源放在 `src/`；原生整合放在 `native/`。修改時應避免把產物目錄 `build/` 當作原始碼來源。
- 變更渲染器、原生庫或設定格式後，至少重新建置一次，並在可用硬體上啟動測試。
- 變更 shader 後，確認 shader 編譯與 `spirv-val` 驗證都通過。
- 變更 denoiser、upscaler 或原生載入邏輯後，執行 `scripts/` 下相關回歸測試，並記錄硬體與驅動程式條件。
- 提交前只加入本次工作的檔案；不要把既有未提交變更、建置日誌、個人設定或下載的 SDK 一起提交。
- 第三方元件可能有獨立授權條款；提交或發佈前請查閱 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 及專案授權檔。

## 8. 參考文件

- [README.md](README.md)：專案概覽、安裝方式與功能狀態。
- [BUILD_GUIDE.md](BUILD_GUIDE.md)：Windows 建置與常見建置問題。
- [README_WINDOWS.md](README_WINDOWS.md)：Windows 執行與優化說明。
- [VULKAN_SDK_INSTALL_GUIDE.md](VULKAN_SDK_INSTALL_GUIDE.md)：Vulkan SDK 安裝說明。
- [scripts/cachyos/README.md](scripts/cachyos/README.md)：CachyOS / Linux 建置與啟動方式。
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：第三方元件與授權注意事項。

---

最後整理：2026-07-19  
文件用途：開發、建置、測試及日常使用前的快速檢查
