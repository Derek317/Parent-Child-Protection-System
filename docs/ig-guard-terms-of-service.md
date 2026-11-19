# IG Guard 使用條款 / Terms of Service

更新 / Updated: 19 November 2026（香港 / Hong Kong）
版本 / Release: 4.9-beta.4
條款修訂 / Terms revision: 2026-11-19-v3
營運 / Operator: Safe Guard — ppefyp2026@gmail.com

## 繁體中文

### 1. 僅限家庭

僅供有適當權限的父母／法定監護人作私人家庭保護。禁止監視陌生人、伴侶、員工、學生、客戶及商業／機構監控。須符合 Instagram 最低年齡（至少13歲）、適用法律及其條款；向青少年解釋範圍、接收者和停止方法，取得所需權限及同意。兩部手機各自接受，父母不能代表所有通訊對象同意。

### 2. 有條件轉傳

接受本條款表示同意下述處理，並不代替 Android 授權或額外啟用擴展擷取。兒童模式配對及 Android 權限具備且未停止收集時，選取的 Instagram 通知／畫面文字加密經 Cloudflare 傳至專門配對並核對的父母／法定監護人閱讀，可含文字、帳戶／對話標籤、方向、時間及提醒原因。一般模式傳送符合規則的傳入提醒；擴展模式另須父母簽署請求及兒童手機本機確認，依子女所選範圍涵蓋傳入、已送出的傳出訊息及可信聯絡人的普通對話脈絡。傳出及可信對象脈絡可分別選擇，擴大範圍須重新確認。不擷取輸入框草稿；歷史草稿仍標示為未確認送出，按封存控制處理。

### 3. 加密及保留

傳輸採 AES-256-GCM；WSS/TLS 於 Cloudflare 終止，內容仍以家庭金鑰加密。Safe Guard／Cloudflare 處理密文及房間、傳送、連線中繼資料，不獲家庭解密金鑰。手機偏好字串及封存內容／標籤採 Keystore AES-GCM 欄位加密及 HMAC 索引；時間、類別、不透明識別碼及狀態仍可見，非整個資料庫檔案加密。父母可閱讀／複製；請保護手機、密碼及配對秘密。

父母介面須通過 Android 生物辨識／裝置憑證驗證，離開前景即重新鎖定；背景接收、解密、封存及收據不因此停止。安全視窗旗標及一般私密通知減少畫面外洩；App 的明確複製操作標示為敏感，並嘗試於60秒後清除仍屬該次複製的剪貼簿項目，不能收回其他副本。生產用途的父母簽署身分使用不可匯出的 Keystore 私鑰；核對後匯入舊金鑰以保留子女已信任的公鑰，再移除舊私鑰偏好值。硬件支援視裝置而定，無法讀取金鑰時不會靜默更換身分。日常遠端金鑰輪換不等於金鑰外洩後復原；「秘密可能外洩：重新安全配對」另建新秘密及簽署身分，須先確認兩部手機安全、停止子女舊擷取，再當面重新配對及核對完整安全代碼，替代秘密不經可疑舊通道傳送。目前的中繼不支援刪除房間，App 會如實顯示為未確認，並保留原有配對；選擇只建立新配對時，不保證中繼已刪除舊資料。舊本機封存隔離保留，舊待傳內容及信任設定捨棄，已被複製的資料無法收回。

新封存預設按記錄時間設定30天到期，父母可選較短期限。個別記錄詳情提供明確限期保留功能，設定一次90天期限，非無限期延長；普通「儲存」標記不延長期限。既有封存不會因升級立即到期刪除；須先預覽並確認採用期限。手機待傳內容及未完成組合設定72小時期限，重試／日常輪換不延長。到期內容於程式運作及處理封存時清理；App 停止運作時，不能保證到期即從儲存裝置移除所有內容。

中繼封包首次入列七天到期，或消耗後較早移除；重複提交不延長原期限。房間註冊資料目前不會自動到期，亦沒有刪除途徑。本機亦可留下無訊息內容的防重播識別碼及統計。刪除／到期識別碼用於防止相同記錄經重播還原，不等於所有副本已抹除。供應商日誌、地區、備份及支援電郵保留期限未核實，上述期限不涵蓋它們。

### 4. 首次選擇及停止

離線顯示與 GitHub 相同的本文；三框預設不勾選，須全選並接受。加密本機收據載日期、應用程式版本、條款修訂及本文 SHA-256。其後啟動／更新不自動重顯；設定可唯讀重閱。實質新增範圍另行取得同意。

接受前停用收集／中繼。拒絕／返回會回 Android 主畫面，不產生接受收據；佇列暫存待處理，原期限不延長，既有雲端／父母副本不刪除。接受不會取消子女已設定的停止狀態。日常更新保留原同意記錄，不代表同意新修訂。

子女可在「擷取範圍與停止」持續停止新擷取及本機待傳送（停止前會先向家長手機送出一則不含訊息內容的通知，並最多等候約 6 秒；之後不再傳送任何內容），選擇捨棄待傳內容或在原期限內暫存。重新開始須明確選擇，會捨棄舊待傳內容並先恢復傳入提醒，擴展擷取須重新確認。儲存範圍選擇也會捨棄舊範圍待傳內容。可另在 Android 同時關閉通知存取及無障礙以撤銷兩種擷取權限；僅關閉權限不等於捨棄佇列。已轉傳或中繼已緩存的內容仍可能送達；已送達封存、Instagram 內容及外部副本須分別處理。每部手機須分別清除應用程式資料。

### 5. 法律範圍

香港第486章第52條針對由個人持有並只與其私人、家庭或家居事務有關，或只是為消閒目的如此持有的個人資料，豁免保障資料原則、第4及5部、第36及38(b)條。須按事實判斷，不自動豁免 Safe Guard／Cloudflare。尊重通訊對象適用權利／私隱、其他法律及合約規則。本通知、接受、確認及 Android 權限均非法律認證或合法保證。法定權利及補救不受影響。

### 6. 服務及查詢

獨立於 Meta，未獲 Meta 批准。擷取、標籤及傳送可不完整、不準確或延遲，無保護保證。穩定 App 未加入新模型；另行進行的合成資料可行性實驗不代表已驗證偵測成效。青少年及其他資料涉及者可電郵上述地址申請查閱、更正或刪除，或須核實身分／權限；初次勿附私訊、密碼或金鑰。

### 7. 三項確認

1. 我已閱讀並同意完整條款。
2. 我同意按已確認範圍加密轉傳至專門核對配對的父母／監護人，明白所述保護及保留期限。
3. 我只作有適當權限、已向子女／家長說明的家庭用途，不把第52條視作自動合法授權。

## English

### 1. Family only

Only authorized private safeguarding by parents/legal guardians is permitted. No surveillance of strangers, partners, staff, students or clients, or commercial/institutional monitoring. Meet Instagram's minimum age (at least 13), applicable law and terms. Explain scope, recipients and stopping to the teen; obtain required authority/consents. Each phone accepts separately; parents cannot consent for all correspondents.

### 2. Conditional forwarding

Acceptance agrees to the processing below; it neither grants Android permissions nor enables expanded capture. With Child Mode pairing, Android permissions and collection active, selected Instagram notification/screen text is encrypted through Cloudflare to the dedicated verified paired parent/legal guardian for reading, including text, account/conversation labels, direction, times and alert reasons. Baseline sends qualifying incoming alerts. Expansion separately requires a signed parent request and local child acknowledgment, covering incoming messages, sent outgoing messages and ordinary trusted-contact context within the child's choices. Outgoing and trusted-context scope can be chosen separately; widening scope requires renewed acknowledgment. Composer drafts are excluded; historical drafts remain labelled unconfirmed as sent and subject to archive controls.

### 3. Encryption and retention

Transport uses AES-256-GCM. WSS/TLS terminates at Cloudflare; payloads remain family-encrypted. Safe Guard/Cloudflare process ciphertext and room/delivery/connection metadata without the family decryption key. Phone preference strings and archive content/labels use Keystore AES-GCM field encryption and HMAC indexes. Times/categories/opaque IDs/status remain visible; the whole database file is not encrypted. Parents can read/copy content; secure phones, passwords and pairing secrets.

The parent UI requires Android biometric/device-credential authentication and relocks on leaving the foreground; background receipt, decryption, archiving and acknowledgments continue. Secure-window flags and generic private notifications reduce screen exposure. Explicit app copy actions mark clipboard items sensitive and attempt to clear the still-current item after 60 seconds; other copies cannot be recalled. Production parent signing identities use non-exportable Keystore private keys. Verified legacy import preserves the public key already trusted by the child before removing the old private-key preference. Hardware backing depends on the device; unavailable keys are not silently replaced. Routine remote rotation is not recovery from key compromise. **秘密可能外洩：重新安全配對** creates fresh secrets and signing identity; first establish that both phones are safe, stop the child's old capture locally, then re-pair in person and compare the full security code. Replacement secrets do not travel through the suspect old channel. The current relay does not support deleting a room, so the app reports this as unconfirmed and keeps the existing pairing; choosing to create a new pairing only gives no assurance that the relay has deleted old data. Old local archives stay isolated, old pending content and trust settings are discarded, and copied old data cannot be recalled.

New archives receive a 30-day expiry based on record time by default; parents can choose a shorter period. Record details offer explicit preservation for a one-time 90-day period, not indefinite renewal; the ordinary saved/bookmark flag does not extend expiry. Existing archives are not immediately expired/deleted on upgrade: preview and confirmation are required to adopt retention. Phone pending content and incomplete assemblies receive 72-hour deadlines, unchanged by retries or routine rotation. Expired content is cleaned during app activity and archive processing; immediate on-disk removal of all content at the deadline is not assured while the app is stopped.

The relay expires buffered packets seven days after first acceptance, or removes them sooner when consumed; duplicates do not extend the original deadline. Room registration metadata currently has no automatic expiry and no deletion route. Local replay identifiers and statistics can remain. Deleted/expired identifiers help prevent restoration of the same record through replay; they do not establish erasure of every copy. Provider logs, regions, backups and support-email retention remain unverified and are outside these deadlines.

### 4. Initial choice and stopping

These exact GitHub terms appear offline. Three boxes start unticked; all are required to accept. An encrypted local receipt saves date, app version, revision and text SHA-256. Launches/updates do not automatically repeat the page; Settings offers read-only review. Materially new scope needs separate consent.

Collection/relay stay off until acceptance. Decline/Back returns to Android Home without an acceptance receipt; queues stay pending within their original deadlines, and earlier cloud/parent copies remain. Acceptance does not cancel a child's persistent stop choice. Ordinary updates preserve the original receipt rather than recording agreement to revised Terms.

The child can use **擷取範圍與停止** (which first sends the parent phone one notice without message content and waits up to about 6 seconds; nothing is sent afterwards) to persistently stop new capture and local pending transfer, choosing discard or hold within the original deadline. Explicit resume discards old pending content and first restarts incoming alerts; expanded capture requires renewed acknowledgment. Saving scope choices also discards old-scope pending content. Both Android notification access and accessibility can additionally be disabled to revoke both capture permissions; permission revocation alone does not discard queues. Already forwarded or relay-buffered content may still arrive. Delivered archives, Instagram content and external copies need separate handling. Clear app data on each phone separately.

### 5. Legal scope

Hong Kong Cap. 486, section 52 exempts personal data held by an individual and concerned only with the management of his personal, family or household affairs, or so held only for recreational purposes, from the Data Protection Principles, Parts 4/5 and sections 36/38(b). Qualification depends on facts; Safe Guard/Cloudflare are not automatically exempt. Respect correspondents' applicable rights/privacy, other laws and contracts. This notice, acceptance, acknowledgment and Android permissions provide no legal certification or guaranteed legality. Statutory rights/remedies continue.

### 6. Service and requests

Independent of Meta; no Meta approval. Capture, labels and delivery can be incomplete, inaccurate or delayed; no guaranteed protection. No new model runs in the stable app; separate synthetic-data feasibility experiments do not establish detection efficacy. Teens/others may email above for access/correction/deletion; identity/authority verification may be needed. Initially omit messages/passwords/keys.

### 7. Three confirmations

1. I have read and agree to the full terms.
2. I agree to encrypted forwarding within acknowledged scope to the dedicated verified paired parent/guardian, with the disclosed security and retention.
3. I will use this only for authorized family purposes with the child/parent informed, without treating section 52 as automatic legal permission.

法例原文 / Primary statute: https://www.elegislation.gov.hk/hk/cap486!en-zh-Hant-HK.pdf?FROMCAPINDEX=Y
