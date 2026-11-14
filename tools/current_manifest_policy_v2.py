"""Normalize two pinned aapt2 spellings, then apply unchanged manifest policy."""
import re
import current_apk_binding as original

def normalize(badging, xml_tree):
    sdk=[line for line in badging.splitlines() if line.startswith(('sdkVersion:', 'minSdkVersion:'))]
    original.require(len(sdk)==1 and sdk[0] in ("sdkVersion:'29'", "minSdkVersion:'29'"), 'Missing/conflicting minimum SDK29 diagnostic')
    if sdk[0].startswith('minSdkVersion:'):
        badging='\n'.join("sdkVersion:'29'" if line==sdk[0] else line for line in badging.split('\n'))
    uri='http://schemas.android.com/apk/res/android'
    if 'A: '+uri+':' in xml_tree:
        declarations=[line for line in xml_tree.splitlines() if line.startswith('N: android=')]
        original.require(len(declarations)==1 and re.fullmatch(r'N: android='+re.escape(uri)+r'(?: \(line=\d+\))?',declarations[0]), 'Unexpected Android namespace diagnostic')
        xml_tree=xml_tree.replace('A: '+uri+':','A: android:')
    return badging,xml_tree

def verify_manifest_policy(badging, xml_tree):
    original.verify_manifest_policy(*normalize(badging,xml_tree))
