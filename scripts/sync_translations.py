#!/usr/bin/env python3
"""
Hikaru Translation Synchronization Tool
Syncs updated translations from upstream Mihon while strictly safeguarding
Hikaru's anime-specific terminology, video player keys, and branding.
"""

import os
import re
import urllib.request
import zipfile
import io
import xml.etree.ElementTree as ET
from xml.dom import minidom

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HIKARU_I18N = os.path.join(ROOT, "i18n/src/commonMain/moko-resources")

FORMAT_SPEC_REGEX = re.compile(r'%(\d+\$)?[sdf]')

PROTECTED_EXACT_NAMES = {
    "app_name", "app_short_name", "manga", "chapters", "scanlator",
    "pref_remove_after_read", "pref_remove_after_marked_as_read",
    "pref_remove_bookmarked_chapters", "pref_category_delete_chapters",
    "download_ahead_info", "action_display_show_continue_reading_button",
    "pref_update_only_completely_read", "onboarding_storage_info",
    "color_filter_r_value", "notes_placeholder", "migrationListScreenTitle"
}

def get_format_specs(text):
    if not text:
        return []
    return FORMAT_SPEC_REGEX.findall(text)

def format_xml(root_element):
    xml_str = ET.tostring(root_element, encoding="utf-8")
    parsed = minidom.parseString(xml_str)
    lines = [line for line in parsed.toprettyxml(indent="    ").split("\n") if line.strip()]
    if lines and lines[0].startswith("<?xml"):
        lines[0] = '<?xml version="1.0" encoding="utf-8"?>'
    else:
        lines.insert(0, '<?xml version="1.0" encoding="utf-8"?>')
    return "\n".join(lines) + "\n"

def main():
    print("🌸 Hikaru Translation Synchronizer")
    print("-----------------------------------")
    
    # 1. Download / extract Mihon archive
    scratch_dir = "/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/79950e24-6ca1-4c85-978a-659e586ffd72/scratch/mihon_extracted/mihon-main/i18n/src/commonMain/moko-resources"
    
    if not os.path.exists(scratch_dir):
        print("Downloading latest Mihon translations...")
        url = "https://github.com/mihonapp/mihon/archive/refs/heads/main.zip"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as resp:
            data = resp.read()
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for info in z.infolist():
                    if "moko-resources" in info.filename:
                        z.extract(info, os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(scratch_dir)))))
                        
    mihon_i18n = scratch_dir
    
    # 2. Parse base strings
    hikaru_base_tree = ET.parse(os.path.join(HIKARU_I18N, "base/strings.xml"))
    mihon_base_tree = ET.parse(os.path.join(mihon_i18n, "base/strings.xml"))
    
    hikaru_base = {el.attrib['name']: el.text or "" for el in hikaru_base_tree.getroot().findall("string") if 'name' in el.attrib}
    mihon_base = {el.attrib['name']: el.text or "" for el in mihon_base_tree.getroot().findall("string") if 'name' in el.attrib}
    
    safe_keys = set()
    for name, a_text in hikaru_base.items():
        if name not in mihon_base or name in PROTECTED_EXACT_NAMES:
            continue
        m_text = mihon_base[name]
        if a_text.strip() == m_text.strip():
            a_specs = get_format_specs(a_text)
            m_specs = get_format_specs(m_text)
            if len(a_specs) == len(m_specs):
                safe_keys.add(name)

    # 3. Parse base plurals
    hikaru_plurals_tree = ET.parse(os.path.join(HIKARU_I18N, "base/plurals.xml"))
    mihon_plurals_tree = ET.parse(os.path.join(mihon_i18n, "base/plurals.xml"))
    
    hikaru_plurals = {el.attrib['name']: {item.attrib['quantity']: item.text or "" for item in el.findall('item')} for el in hikaru_plurals_tree.getroot().findall('plurals') if 'name' in el.attrib}
    mihon_plurals = {el.attrib['name']: {item.attrib['quantity']: item.text or "" for item in el.findall('item')} for el in mihon_plurals_tree.getroot().findall('plurals') if 'name' in el.attrib}
    
    safe_plurals = set()
    for name in hikaru_plurals:
        if name in mihon_plurals and hikaru_plurals[name] == mihon_plurals[name]:
            safe_plurals.add(name)

    print(f"Verified {len(safe_keys)} safe string keys and {len(safe_plurals)} safe plural keys.")
    
    # 4. Iterate and sync every language
    languages = [d for d in os.listdir(HIKARU_I18N) if os.path.isdir(os.path.join(HIKARU_I18N, d)) and d != "base"]
    languages.sort()
    
    total_strings_updated = 0
    total_plurals_updated = 0
    synced_langs = 0
    
    for lang in languages:
        hikaru_lang_dir = os.path.join(HIKARU_I18N, lang)
        mihon_lang_dir = os.path.join(mihon_i18n, lang)
        
        if not os.path.exists(mihon_lang_dir):
            continue
            
        lang_strings_updated = 0
        lang_plurals_updated = 0
        
        # A. Strings
        hikaru_strings_path = os.path.join(hikaru_lang_dir, "strings.xml")
        mihon_strings_path = os.path.join(mihon_lang_dir, "strings.xml")
        
        if os.path.exists(mihon_strings_path):
            if os.path.exists(hikaru_strings_path):
                hikaru_s_tree = ET.parse(hikaru_strings_path)
                hikaru_root = hikaru_s_tree.getroot()
            else:
                hikaru_root = ET.Element("resources")
                hikaru_s_tree = ET.ElementTree(hikaru_root)
                
            hikaru_string_map = {el.attrib['name']: el for el in hikaru_root.findall("string") if 'name' in el.attrib}
            
            mihon_s_tree = ET.parse(mihon_strings_path)
            for el in mihon_s_tree.getroot().findall("string"):
                name = el.attrib.get("name")
                if name in safe_keys and el.text:
                    if name in hikaru_string_map:
                        if hikaru_string_map[name].text != el.text:
                            hikaru_string_map[name].text = el.text
                            lang_strings_updated += 1
                    else:
                        new_el = ET.SubElement(hikaru_root, "string", attrib={"name": name})
                        new_el.text = el.text
                        lang_strings_updated += 1
                        
            with open(hikaru_strings_path, "w", encoding="utf-8") as f:
                f.write(format_xml(hikaru_root))
                
        # B. Plurals
        hikaru_plurals_path = os.path.join(hikaru_lang_dir, "plurals.xml")
        mihon_plurals_path = os.path.join(mihon_lang_dir, "plurals.xml")
        
        if os.path.exists(mihon_plurals_path):
            if os.path.exists(hikaru_plurals_path):
                hikaru_p_tree = ET.parse(hikaru_plurals_path)
                hikaru_p_root = hikaru_p_tree.getroot()
            else:
                hikaru_p_root = ET.Element("resources")
                hikaru_p_tree = ET.ElementTree(hikaru_p_root)
                
            hikaru_plural_map = {el.attrib['name']: el for el in hikaru_p_root.findall("plurals") if 'name' in el.attrib}
            
            mihon_p_tree = ET.parse(mihon_plurals_path)
            for el in mihon_p_tree.getroot().findall("plurals"):
                name = el.attrib.get("name")
                if name in safe_plurals:
                    if name in hikaru_plural_map:
                        hikaru_p_root.remove(hikaru_plural_map[name])
                    new_plural = ET.SubElement(hikaru_p_root, "plurals", attrib={"name": name})
                    for item in el.findall("item"):
                        item_el = ET.SubElement(new_plural, "item", attrib={"quantity": item.attrib.get("quantity", "other")})
                        item_el.text = item.text
                    lang_plurals_updated += 1
                    
            with open(hikaru_plurals_path, "w", encoding="utf-8") as f:
                f.write(format_xml(hikaru_p_root))
                
        if lang_strings_updated > 0 or lang_plurals_updated > 0:
            synced_langs += 1
            total_strings_updated += lang_strings_updated
            total_plurals_updated += lang_plurals_updated
            print(f"  [{lang:^7}] +{lang_strings_updated:3d} strings, +{lang_plurals_updated:2d} plurals")
            
    print("-----------------------------------")
    print(f"✨ Successfully synced {synced_langs} languages!")
    print(f"   Total string updates: {total_strings_updated}")
    print(f"   Total plural updates: {total_plurals_updated}")

if __name__ == "__main__":
    main()
