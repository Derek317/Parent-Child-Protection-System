> **DRAFT, not in force.** Written on 19 November 2026 for the scores-only family mode. It must be reviewed by Derek and by a Hong Kong privacy lawyer before release. It is not bundled in the app, and nothing in it claims approval by Meta or legal clearance.

> Drafting note: passages about the scores-only family mode describe the approved design and implementation plan of 19 November 2026, not shipped code. Check each against the build before release. Text in square brackets is an open point, not wording to publish.

# IG Guard 使用條款 / Terms of Service

更新 / Updated: 19 November 2026（香港 / Hong Kong）草稿 / draft
版本 / Release: 4.9-beta.4（來源草稿 / source draft）
條款修訂 / Terms revision: 發佈時訂定 / to be assigned at release（現行 / live: 2026-11-18-v2）
營運 / Operator: Safe Guard — ppefyp2026@gmail.com

## 繁體中文

### 1. 僅限家庭

僅供有適當權限的父母／法定監護人作私人家庭保護。禁止監視陌生人、伴侶、員工、學生、客戶及商業／機構監控。須符合 Instagram 最低年齡（至少13歲）、適用法律及其條款；向青少年解釋範圍、接收者和停止方法，取得所需權限及同意。兩部手機各自接受，父母不能代表所有通訊對象同意。

### 2. 有條件轉傳

接受本條款表示同意下述處理，並不代替 Android 授權或額外啟用擴展擷取。兒童模式配對及 Android 權限具備且未停止收集時，選取的 Instagram 通知／畫面文字加密經 Cloudflare 傳至專門配對並核對的父母／法定監護人閱讀，可含文字、帳戶／對話標籤、方向、時間及提醒原因。一般模式傳送符合規則的傳入提醒；擴展模式另須父母簽署請求及兒童手機本機確認，依子女所選範圍涵蓋傳入、已送出的傳出訊息及可信聯絡人的普通對話脈絡。傳出及可信對象脈絡可分別選擇，擴大範圍須重新確認。不擷取輸入框草稿；歷史草稿仍標示為未確認送出，按封存控制處理。如父母開啟下述2A的可選「僅分數家庭模式」，該模式下不轉傳訊息文字。

### 2A. 僅分數家庭模式（可選）

此模式可選，預設關閉，由父母在父母手機開啟。父母手機須待子女手機回報已有效安裝模型檔案，才能開啟。子女須先在子女手機的獨立頁面同意一次；同意之前，不會儲存或分析任何內容。子女未同意時，父母只會看到「等待子女同意」，不能代子女同意，原有模式繼續運作。此同意獨立於第7節三項確認及第4節的條款接受收據。

**分析範圍。** 只分析 App 已透過通知及畫面文字擷取的一對一 Instagram 對話。群組對話及類型不明的對話不會儲存或分析，只會在報告中計算數目。許多已擷取的畫面對話，App 未必能辨認其類型，這些對話不會被分析。

**分析地點。** 分析在子女手機上進行，使用手動安裝的模型檔案（約1.1 GB）。App 核對檔案大小及 SHA-256，不符即拒絕。手機閒置並在充電時運行；如超過48小時沒有運行，只須充電便會運行。此模式下，不會有聊天文字傳送至任何伺服器或父母手機。手機須有約8 GB記憶體，否則不能使用此模式，App 會說明。

**子女手機上的新增加密儲存。** 擷取的一對一訊息會存入子女手機上的加密儲存，沿用現有本機保險庫的裝置綁定金鑰。每個對話只保留最新60則訊息，不保留超過14日的訊息。每個對話以最新30則訊息評分，報告的分數是14日內所見的最高分。子女撤回時，此儲存及未傳送的分數會被刪除。[待確認：父母關閉此模式、模型檔案被移除或其他情況下，儲存是否同樣即時刪除。] 清除 App 資料或解除安裝亦會移除此儲存。

**父母會收到。** 每個對話的對象帳戶標籤、0至100的分數或「需要檢視」，以及時間。關鍵字及非核准聯絡人規則仍在子女手機運行；此模式下的提醒只載規則類別、帳戶標籤及時間，不載訊息文字、引文、摘要或所符合的字句。另會收到子女狀態：模型狀態、已同意或等待中、已暫停及暫停時間、已撤回、上次運行時間、已評分及因類型不明而略過的對話數目。

**子女的控制。** App 會一直顯示分析已開啟。子女可隨時暫停：暫停即時生效（包括進行中的運行），並停止儲存及評分；父母會在中繼可連線時獲告知，並看到「子女已暫停」及時間；繼續無須重新同意。子女亦可撤回：刪除儲存及未傳送的分數，並通知父母；父母已收到的分數仍留在父母手機；再次開啟須重新同意。撤回後，手機不會改為轉傳文字，提醒仍不含文字，父母會看到「子女已撤回」；只有父母關閉此模式，才會回復原有模式。

**已配對家庭。** 切換至此模式後，父母不再收到新的訊息文字。父母手機上既有的封存會保留，直至父母自行刪除。兩部手機在切換前會顯示將會改變的內容。

**對話的另一方。** 每個對話的另一方，其訊息會存入並分析於子女手機，其帳戶標籤會傳送至父母。他們未被邀請同意；父母或子女的同意不代表他們。

**模型的限制。** 模型只在虛構對話上測試過，從未評分真實對話。它可能漏報，也可能誤報。分數只是訊號，不是查證結果或結論。擷取並不完整，分數只涵蓋已擷取的內容。此模式下父母無法閱讀訊息，即使在緊急情況下亦然。本條款不保證偵測準確或任何安全結果。速度、耗電及發熱在真機上尚未量度。

**與 Meta 的關係。** 此模式沿用現有擷取方式。IG Guard 獨立於 Meta，未獲 Meta 批准；未實作官方 Instagram API 及資料可攜整合。

### 3. 加密及保留

傳輸採 AES-256-GCM；WSS/TLS 於 Cloudflare 終止，內容仍以家庭金鑰加密。Safe Guard／Cloudflare 處理密文及房間、傳送、連線中繼資料，不獲家庭解密金鑰。手機偏好字串及封存內容／標籤採 Keystore AES-GCM 欄位加密及 HMAC 索引；時間、類別、不透明識別碼及狀態仍可見，非整個資料庫檔案加密。父母可閱讀／複製；請保護手機、密碼及配對秘密。

父母介面須通過 Android 生物辨識／裝置憑證驗證，離開前景即重新鎖定；背景接收、解密、封存及收據不因此停止。安全視窗旗標及一般私密通知減少畫面外洩；App 的明確複製操作標示為敏感，並嘗試於60秒後清除仍屬該次複製的剪貼簿項目，不能收回其他副本。生產用途的父母簽署身分使用不可匯出的 Keystore 私鑰；核對後匯入舊金鑰以保留子女已信任的公鑰，再移除舊私鑰偏好值。硬件支援視裝置而定，無法讀取金鑰時不會靜默更換身分。日常遠端金鑰輪換不等於金鑰外洩後復原；「秘密可能外洩：重新安全配對」另建新秘密及簽署身分，須先確認兩部手機安全、停止子女舊擷取，再當面重新配對及核對完整安全代碼，替代秘密不經可疑舊通道傳送。可先驗證清除舊中繼房間；明確選擇離線建立則不保證中繼已刪除。舊本機封存隔離保留，舊待傳內容及信任設定捨棄，已被複製的資料無法收回。

新封存預設按記錄時間設定30天到期，父母可選較短期限。個別記錄詳情提供明確限期保留功能，設定一次90天期限，非無限期延長；普通「儲存」標記不延長期限。既有封存不會因升級立即到期刪除；須先預覽並確認採用期限。手機待傳內容及未完成組合設定72小時期限，重試／日常輪換不延長。到期內容於程式運作及處理封存時清理；App 停止運作時，不能保證到期即從儲存裝置移除所有內容。

更新後的中繼來源設定封包首次接受後72小時到期，或消耗後較早移除，重複提交不延長原期限。房間註冊於90天無有效活動後退役；經驗證的父母退役／清除可刪除該房間佇列及註冊，再留90天退役標記。防重播封包中繼資料可保留至到期後90天；本機亦可留下無訊息內容的防重播識別碼及統計。刪除／到期識別碼用於防止相同記錄經重播還原，不等於所有副本已抹除。中繼部署另行核實；供應商日誌、地區、備份及支援電郵保留期限未核實，上述期限不涵蓋它們。

### 4. 首次選擇及停止

離線顯示與 GitHub 相同的本文；三框預設不勾選，須全選並接受。加密本機收據載日期、應用程式版本、條款修訂及本文 SHA-256。其後啟動／更新不自動重顯；設定可唯讀重閱。實質新增範圍另行取得同意。

接受前停用收集／中繼。拒絕／返回會回 Android 主畫面，不產生接受收據；佇列暫存待處理，原期限不延長，既有雲端／父母副本不刪除。接受不會取消子女已設定的停止狀態。日常更新保留原同意記錄，不代表同意新修訂。

子女可在「擷取範圍與停止」持續停止新擷取及本機待傳送，選擇捨棄待傳內容或在原期限內暫存。重新開始須明確選擇，會捨棄舊待傳內容並先恢復傳入提醒，擴展擷取須重新確認。儲存範圍選擇也會捨棄舊範圍待傳內容。可另在 Android 同時關閉通知存取及無障礙以撤銷兩種擷取權限；僅關閉權限不等於捨棄佇列。已轉傳或中繼已緩存的內容仍可能送達；已送達封存、Instagram 內容及外部副本須分別處理。每部手機須分別清除應用程式資料。僅分數家庭模式下的暫停及撤回見2A。

### 5. 法律範圍

香港第486章第52條針對由個人持有並只與其私人、家庭或家居事務有關，或只是為消閒目的如此持有的個人資料，豁免保障資料原則、第4及5部、第36及38(b)條。須按事實判斷，不自動豁免 Safe Guard／Cloudflare。尊重通訊對象適用權利／私隱、其他法律及合約規則。本通知、接受、確認及 Android 權限均非法律認證或合法保證。法定權利及補救不受影響。

### 6. 服務及查詢

獨立於 Meta，未獲 Meta 批准。擷取、標籤及傳送可不完整、不準確或延遲，無保護保證。App 本身不內含模型。僅在父母開啟、子女同意的僅分數家庭模式下，手動安裝的模型檔案才在子女手機運行，其限制見2A，並不代表已驗證偵測成效。另行進行的合成資料可行性實驗亦不代表已驗證偵測成效。青少年及其他資料涉及者可電郵上述地址申請查閱、更正或刪除，或須核實身分／權限；初次勿附私訊、密碼或金鑰。

### 7. 三項確認

1. 我已閱讀並同意完整條款。
2. 我同意按已確認範圍加密轉傳至專門核對配對的父母／監護人，明白所述保護及保留期限。
3. 我只作有適當權限、已向子女／家長說明的家庭用途，不把第52條視作自動合法授權。

## English

### 1. Family only

Only authorized private safeguarding by parents/legal guardians is permitted. No surveillance of strangers, partners, staff, students or clients, or commercial/institutional monitoring. Meet Instagram's minimum age (at least 13), applicable law and terms. Explain scope, recipients and stopping to the teen; obtain required authority/consents. Each phone accepts separately; parents cannot consent for all correspondents.

### 2. Conditional forwarding

Acceptance agrees to the processing below; it neither grants Android permissions nor enables expanded capture. With Child Mode pairing, Android permissions and collection active, selected Instagram notification/screen text is encrypted through Cloudflare to the dedicated verified paired parent/legal guardian for reading, including text, account/conversation labels, direction, times and alert reasons. Baseline sends qualifying incoming alerts. Expansion separately requires a signed parent request and local child acknowledgment, covering incoming messages, sent outgoing messages and ordinary trusted-contact context within the child's choices. Outgoing and trusted-context scope can be chosen separately; widening scope requires renewed acknowledgment. Composer drafts are excluded; historical drafts remain labelled unconfirmed as sent and subject to archive controls. If a parent switches on the optional scores-only family mode in section 2A, message text is not forwarded in that mode.

### 2A. Scores-only family mode (optional)

This mode is optional and off by default. A parent switches it on in the parent phone. The switch stays disabled until the child's phone has reported a valid model file. The child must first agree once, on a separate page on the child's phone; until then nothing is stored or analysed. If the child has not agreed, the parent sees "waiting for your child to agree", cannot agree for the child, and the earlier mode keeps running. This agreement is separate from the three confirmations in section 7 and from the Terms receipt in section 4.

**What is analysed.** Only one-to-one Instagram conversations that the app already captures through notifications and screen text. A conversation that shows a second other person in a notification is treated as a group, what was kept is deleted, and it is only counted in the report. The app cannot tell a group from a one-to-one chat read from the screen, so a group chat the child opens on the phone may be analysed. A chat the app knows to be a group is not analysed.

**Where it runs.** Analysis runs on the child's phone, using a model file (about 1.1 GB) installed by hand. The app checks the file's size and SHA-256 and refuses any other file. It runs while the phone is idle and charging; if no run has happened for 48 hours, charging alone is enough. In this mode no chat text is sent to any server or to the parent's phone. The phone needs about 8 GB of memory; otherwise the mode is unavailable and the app says so.

**New encrypted store on the child's phone.** Captured one-to-one messages are kept in an encrypted store on the child's phone, using the same device-bound keys as the existing local vault. It keeps the latest 60 messages per conversation and nothing older than 14 days. Each conversation is scored on its latest 30 messages, and the reported score is the highest seen in the last 14 days. When the child withdraws, the store and any unsent scores are deleted. [To be confirmed: whether the store is deleted at once when the parent switches the mode off, when the model file is removed, or in other cases.] Clearing app data or uninstalling also removes the store.

**What the parent receives.** For each conversation: the contact's account label, a score from 0 to 100 or "needs review", and the time. The keyword and unapproved-contact rules keep running on the child's phone; in this mode their alerts carry only the rule category, the account label and the time, with no message text, quote, summary or matched phrase. The parent also sees the child's status: model state, agreed or waiting, paused and when, withdrawn, time of the last run, the number of conversations scored, and the number skipped because their type is unknown.

**The child's controls.** The app always shows that analysis is on. The child can pause at any time: it takes effect at once, including a run in progress, and stops storing as well as scoring. The parent is told as soon as the relay is reachable and sees "paused by your child" and when. Resuming needs no new consent. The child can also withdraw: this deletes the store and unsent scores and tells the parent. Scores the parent already received stay on the parent's phone. Switching the mode on again needs the child's agreement again. After withdrawal the phone does not fall back to forwarding text: alerts stay free of message text, the parent sees "withdrawn by your child", and only the parent switching the mode off returns the family to the earlier mode.

**Families already paired.** After the switch, the parent no longer receives new message text. The parent's existing archive stays on the parent's phone until the parent deletes it. Both phones show what will change before the switch.

**The other person in each conversation.** Their messages are stored and analysed on the child's phone and their account label is sent to the parent. They have not been asked to agree, and the agreement of the parent or child does not speak for them.

**Limits of the model.** The model has been tested only on invented conversations; no real conversation has been scored. It can miss concerning conversations and it can wrongly flag harmless ones. A score is a signal, not a finding or a conclusion. Capture is incomplete, so scores cover only what was captured. In this mode parents cannot read messages, even in an emergency. These Terms make no promise of accurate detection or of any safety outcome. Speed, battery use and heat on real phones have not been measured.

**Relationship with Meta.** This mode uses the existing capture. IG Guard is independent of Meta and has no Meta approval. Official Instagram API and portability integrations are not implemented.

### 3. Encryption and retention

Transport uses AES-256-GCM. WSS/TLS terminates at Cloudflare; payloads remain family-encrypted. Safe Guard/Cloudflare process ciphertext and room/delivery/connection metadata without the family decryption key. Phone preference strings and archive content/labels use Keystore AES-GCM field encryption and HMAC indexes. Times/categories/opaque IDs/status remain visible; the whole database file is not encrypted. Parents can read/copy content; secure phones, passwords and pairing secrets.

The parent UI requires Android biometric/device-credential authentication and relocks on leaving the foreground; background receipt, decryption, archiving and acknowledgments continue. Secure-window flags and generic private notifications reduce screen exposure. Explicit app copy actions mark clipboard items sensitive and attempt to clear the still-current item after 60 seconds; other copies cannot be recalled. Production parent signing identities use non-exportable Keystore private keys. Verified legacy import preserves the public key already trusted by the child before removing the old private-key preference. Hardware backing depends on the device; unavailable keys are not silently replaced. Routine remote rotation is not recovery from key compromise. **秘密可能外洩：重新安全配對** creates fresh secrets and signing identity; first establish that both phones are safe, stop the child's old capture locally, then re-pair in person and compare the full security code. Replacement secrets do not travel through the suspect old channel. The flow offers authenticated old-room purge; explicitly choosing offline creation gives no assurance of relay deletion. Old local archives stay isolated, old pending content and trust settings are discarded, and copied old data cannot be recalled.

New archives receive a 30-day expiry based on record time by default; parents can choose a shorter period. Record details offer explicit preservation for a one-time 90-day period, not indefinite renewal; the ordinary saved/bookmark flag does not extend expiry. Existing archives are not immediately expired/deleted on upgrade: preview and confirmation are required to adopt retention. Phone pending content and incomplete assemblies receive 72-hour deadlines, unchanged by retries or routine rotation. Expired content is cleaned during app activity and archive processing; immediate on-disk removal of all content at the deadline is not assured while the app is stopped.

Updated relay source expires packets 72 hours after first acceptance, or removes them sooner when consumed; duplicates do not extend the original deadline. Room registrations retire after 90 days without qualifying activity. Authenticated parent retirement/purge removes that room's queues and registration, leaving a retirement marker for a further 90 days. Content-free packet replay metadata can remain for 90 days after expiry. Local replay identifiers and statistics can also remain. Deleted/expired identifiers help prevent restoration of the same record through replay; they do not establish erasure of every copy. Relay deployment needs separate verification. Provider logs, regions, backups and support-email retention remain unverified and are outside these deadlines.

### 4. Initial choice and stopping

These exact GitHub terms appear offline. Three boxes start unticked; all are required to accept. An encrypted local receipt saves date, app version, revision and text SHA-256. Launches/updates do not automatically repeat the page; Settings offers read-only review. Materially new scope needs separate consent.

Collection/relay stay off until acceptance. Decline/Back returns to Android Home without an acceptance receipt; queues stay pending within their original deadlines, and earlier cloud/parent copies remain. Acceptance does not cancel a child's persistent stop choice. Ordinary updates preserve the original receipt rather than recording agreement to revised Terms.

The child can use **擷取範圍與停止** to persistently stop new capture and local pending transfer, choosing discard or hold within the original deadline. Explicit resume discards old pending content and first restarts incoming alerts; expanded capture requires renewed acknowledgment. Saving scope choices also discards old-scope pending content. Both Android notification access and accessibility can additionally be disabled to revoke both capture permissions; permission revocation alone does not discard queues. Already forwarded or relay-buffered content may still arrive. Delivered archives, Instagram content and external copies need separate handling. Clear app data on each phone separately. Pause and withdrawal in the scores-only family mode are described in section 2A.

### 5. Legal scope

Hong Kong Cap. 486, section 52 exempts personal data held by an individual and concerned only with the management of his personal, family or household affairs, or so held only for recreational purposes, from the Data Protection Principles, Parts 4/5 and sections 36/38(b). Qualification depends on facts; Safe Guard/Cloudflare are not automatically exempt. Respect correspondents' applicable rights/privacy, other laws and contracts. This notice, acceptance, acknowledgment and Android permissions provide no legal certification or guaranteed legality. Statutory rights/remedies continue.

### 6. Service and requests

Independent of Meta; no Meta approval. Capture, labels and delivery can be incomplete, inaccurate or delayed; no guaranteed protection. No model is included in the app. A model file installed by hand runs on the child's phone only in the scores-only family mode, when a parent has switched it on and the child has agreed; its limits are in section 2A and it does not establish detection efficacy. Separate synthetic-data feasibility experiments also do not establish detection efficacy. Teens/others may email above for access/correction/deletion; identity/authority verification may be needed. Initially omit messages/passwords/keys.

### 7. Three confirmations

1. I have read and agree to the full terms.
2. I agree to encrypted forwarding within acknowledged scope to the dedicated verified paired parent/guardian, with the disclosed security and retention.
3. I will use this only for authorized family purposes with the child/parent informed, without treating section 52 as automatic legal permission.

法例原文 / Primary statute: https://www.elegislation.gov.hk/hk/cap486!en-zh-Hant-HK.pdf?FROMCAPINDEX=Y
