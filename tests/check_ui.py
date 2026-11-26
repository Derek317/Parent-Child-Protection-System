"""Source safety and colour checks; device behavior needs device validation."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET
S=(Path(__file__).resolve().parents[1]/'MainActivity.kt').read_text()
def contrast(a,b):
 def lum(h):
  v=[int(h[i:i+2],16)/255 for i in (1,3,5)]
  return sum((x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4)*w for x,w in zip(v,(.2126,.7152,.0722)))
 a,b=sorted((lum(a),lum(b)));return (b+.05)/(a+.05)
class Checks(unittest.TestCase):
 def test_chat_reader_receives_accessibility_node_identifiers(self):
  workflow=(Path(__file__).resolve().parents[1]/'.github/workflows/build-apk.yml').read_text()
  config=re.search(r'<accessibility-service\b.*?/>',workflow,re.S).group(0)
  flags=ET.fromstring(config).attrib['{http://schemas.android.com/apk/res/android}accessibilityFlags'].split('|')
  self.assertIn('flagReportViewIds',flags,'Toolbar and message identifiers must be available on actual devices')
 def test_settings_never_unlock(self):
  self.assertNotIn('grantTemporaryUnlock',S.split('private fun openDirectNotificationToggle()',1)[1].split('private fun syncRoleServices()',1)[0])
 def test_clipboard_is_only_written_through_the_sensitive_helper(self):
  root=Path(__file__).resolve().parents[1]
  for source in root.glob('*.kt'):
   if source.name!='SecurityControls.kt':
    self.assertNotIn('setPrimaryClip',source.read_text(),source.name+' must copy through SensitiveClipboard')
 def test_pairing_does_not_depend_on_temporary_unlock(self):
  body=S.split('fun applyPairing(',1)[1].split('fun noteInviteLinkOnChild(',1)[0]
  self.assertNotIn('isTemporarilyUnlocked',body)
 def test_checkpoint_logs_never_interpolate_contact_identifiers(self):
  for line in S.splitlines():
   if 'addCheckpointLog(' in line and 'fun addCheckpointLog' not in line:
    self.assertNotRegex(line,r'\$\{?(\w*[Ss]ender|resolved\w*|clean|detected|handle)\b|@\$',line.strip())
 def test_relay_retention_claims_match_the_relay(self):
  root=Path(__file__).resolve().parents[1]
  ttl=re.search(r'const TTL = (\d+) \* 24',(root/'relay/src/worker.js').read_text()).group(1)
  self.assertEqual(ttl,'7','relay packet lifetime changed: update the in-app texts')
  idle=re.search(r'const REGISTRATION_TTL = (\d+) \* 24',(root/'relay/src/worker.js').read_text()).group(1)
  self.assertEqual(idle,'90','relay registration lifetime changed: update the Terms and privacy texts')
  for source in list(root.glob('*.kt'))+list((root/'ui-res').rglob('*.xml')):
   for number,line in enumerate(source.read_text().splitlines(),1):
    for clause in re.split('[。；;]',line):
     if '中繼' in clause and '72 小時' in clause:
      self.fail(f'{source.name}:{number} states relay retention that the relay does not implement: {clause[:60]}')
 def test_user_facing_documents_have_no_branch_or_draft_language(self):
  root=Path(__file__).resolve().parents[1]
  names=['README.md','docs/ig-guard-terms-of-service.md','docs/ig-guard-privacy-policy.md','docs/ig-guard-data-deletion.md','docs/local-storage-security.md','docs/releases/5.0.md','docs/releases/5.1.md','docs/releases/5.2.md','docs/releases/6.0.md']
  banned=['本分支','on this branch','source draft','來源草稿','security/hardening-2026-11-17','not an announcement of a published APK','Local commits do not']
  for name in names:
   text=(root/name).read_text()
   for phrase in banned:self.assertNotIn(phrase,text,f'{name}: {phrase}')
  consent=(root/'AppConsent.kt').read_text()
  revision=re.search(r'VERSION = "([^"]+)"',consent).group(1)
  self.assertIn('Terms revision: '+revision,(root/'docs/ig-guard-terms-of-service.md').read_text())
  for name in names[2:4]+names[5:]:
   self.assertNotIn('2026-11-18-v2',(root/name).read_text(),name)
 def test_documents_match_receipt_time_retention_and_key_fallback(self):
  root=Path(__file__).resolve().parents[1]
  names=['README.md','docs/ig-guard-terms-of-service.md','docs/ig-guard-privacy-policy.md','docs/local-storage-security.md','docs/releases/5.0.md','CHANGELOG.md','docs/releases/5.1.md','docs/releases/5.2.md','docs/releases/6.0.md']
  for name in names:
   text=(root/name).read_text()
   for phrase in ['based on record time','按記錄時間','from record time','measured from record time']:self.assertNotIn(phrase,text,f'{name}: {phrase}')
  for name in names[:5]:
   text=(root/name).read_text()
   self.assertTrue('keeps using that existing key' in text or '繼續使用原有金鑰' in text or 'existing legacy key keeps working' in text,f'{name} must state the key-migration fallback')
  consent=(root/'AppConsent.kt').read_text()
  self.assertIn('若移入失敗',consent)
  self.assertNotIn('簽署金鑰不匯出。',consent)
 def test_changelog_has_one_entry_for_this_version(self):
  text=(Path(__file__).resolve().parents[1]/'CHANGELOG.md').read_text()
  self.assertEqual(1,text.count('## 4.9-beta.4'))
  self.assertEqual(1,text.count('## 5.0 '))
  self.assertEqual(1,text.count('## 5.1 '))
  self.assertEqual(1,text.count('## 5.2 '))
  self.assertEqual(1,text.count('## 6.0 '))
  self.assertEqual(1,text.count('## 6.0.3 '))
  self.assertNotIn('## Unreleased',text)
 def test_monitoring_is_declared(self):
  workflow=(Path(__file__).resolve().parents[1]/'.github/workflows/build-apk.yml').read_text()
  self.assertTrue('android:name="isMonitoringTool" android:value="child_monitoring"' in workflow, "Missing parental-monitoring declaration")
 def test_network_does_not_hold_ui_state_lock(self):
  poll=S.split('fun pollChannelOnce(',1)[1].split('fun processIncomingRelayLine(',1)[0]
  self.assertNotIn('synchronized(pollLock)',poll, "Network I/O must not hold the statistics/UI state lock")
 def test_contrast(self):
  palettes=re.findall(r'ThemeColors\(\s*bgCanvas.*?\n            \)',S,re.S)
  self.assertEqual(len(palettes),2)
  for raw in palettes:
   p=dict(re.findall(r'(\w+) = "(#[0-9A-Fa-f]{6})"',raw))
   for a,b in [('textPrimary','bgCard'),('textSecondary','bgCard'),('textMuted','bgInput'),('btnPrimaryText','btnPrimaryBg'),('btnSecondaryText','btnSecondaryBg'),('accentSeafoam','bgCard'),('accentSeafoam','bgHero'),('warmHoney','bgCard'),('warmHoney','bgHero'),('warmCoral','bgHero'),('warmCoral','bgCard'),('riskLow','bgCard'),('riskLow','bgHero'),('warningButtonText','warningButtonBg'),
              ('textPrimary','bgCanvas'),('textSecondary','bgCanvas'),('accentSeafoam','bgCanvas'),('warmHoney','bgCanvas'),('warmCoral','bgCanvas'),('riskLow','bgCanvas'),
              ('textPrimary','bgHero'),('textSecondary','bgHero'),('textMuted','bgCard'),('navPillInk','navPill')]:
    with self.subTest(pair=(a,b,p[a],p[b])): self.assertGreaterEqual(contrast(p[a],p[b]),4.5,(a,b,p[a],p[b]))
 def test_contrast_inside_emphasised_notice_cards(self):
  # The dark palette with the onNotice() overrides (light mode's navy card).
  palettes=re.findall(r'ThemeColors\(\s*bgCanvas.*?\n            \)',S,re.S)
  block=S.split('fun onNotice(',1)[1].split('\n    )',1)[0]
  n={**dict(re.findall(r'(\w+) = "(#[0-9A-Fa-f]{6})"',palettes[1])),**dict(re.findall(r'(\w+) = "(#[0-9A-Fa-f]{6})"',block))}
  self.assertEqual(n['bgCard'],'#12345A')
  for a,b in [('textPrimary','bgCard'),('textSecondary','bgCard'),('textMuted','bgCard'),('accentSeafoam','bgCard'),('warmHoney','bgCard'),('warmCoral','bgCard'),('riskLow','bgCard'),
              ('btnPrimaryText','btnPrimaryBg'),('btnSecondaryText','btnSecondaryBg')]:
   self.assertGreaterEqual(contrast(n[a],n[b]),4.5,('notice',a,b,n[a],n[b]))
 def test_dialogs_are_built_only_through_the_themed_alert_helper(self):
  self.assertEqual(S.count('AlertDialog.Builder('),1,'build dialogs with alert() so they are themed, tracked and session-guarded')
if __name__=='__main__': unittest.main()
