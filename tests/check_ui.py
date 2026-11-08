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
   for a,b in [('textPrimary','bgCard'),('textSecondary','bgCard'),('textMuted','bgInput'),('btnPrimaryText','btnPrimaryBg'),('btnSecondaryText','btnSecondaryBg'),('warmHoney','bgCard'),('warmCoral','bgCard'),('warningButtonText','warningButtonBg')]:
    self.assertGreaterEqual(contrast(p[a],p[b]),4.5,(a,b,p[a],p[b]))
if __name__=='__main__': unittest.main()
