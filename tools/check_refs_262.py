"""比对插件 1.5.17 字节码引用 vs 2026.2.5 平台：找出 262 缺失的类/方法引用。

用法：
  python tools/check_refs_262.py <plugin.jar> <idea-2026.2.5-win 目录>

1. 从插件 jar 每个 class 提取方法/字段引用 (owner, name)。
2. 从 2026.2.5 平台所有 jar 收集类的"声明方法 + 引用方法"集合。
3. 报告插件引用中在 262 平台找不到的项。
"""
import sys, zipfile, os, glob, struct
from collections import defaultdict


def parse_class(data):
    """返回 (class_name, declared_methods:set, refs:set[(owner,name)])。"""
    if data[0:4] != b'\xca\xfe\xba\xbe':
        return None, set(), set()
    pool = {}
    idx = 8
    n = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
    i = 1
    while i < n:
        tag = data[idx]; idx += 1
        if tag == 7:
            pool[i] = ('Class', struct.unpack('>H', data[idx:idx+2])[0]); idx += 2
        elif tag in (9, 10, 11):
            c, nt = struct.unpack('>HH', data[idx:idx+4]); idx += 4
            pool[i] = ('Ref', tag, c, nt)
        elif tag == 8:
            pool[i] = ('Str', struct.unpack('>H', data[idx:idx+2])[0]); idx += 2
        elif tag == 15:
            idx += 3
        elif tag == 16:
            idx += 2
        elif tag in (17, 18):
            idx += 4
        elif tag == 19 or tag == 20:
            pool[i] = ('Mod', struct.unpack('>H', data[idx:idx+2])[0]); idx += 2
        elif tag in (3, 4):
            idx += 4
        elif tag in (5, 6):
            idx += 8; i += 1
        elif tag == 1:
            ln = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
            pool[i] = ('Utf8', data[idx:idx+ln]); idx += ln
        elif tag == 12:
            a, b = struct.unpack('>HH', data[idx:idx+4]); idx += 4
            pool[i] = ('NAT', a, b)
        else:
            return None, set(), set()
        i += 1

    def utf8(k):
        e = pool.get(k)
        return e[1].decode('utf-8', 'replace') if e and e[0] == 'Utf8' else ''

    def cls(k):
        e = pool.get(k)
        return utf8(e[1]).replace('/', '.') if e and e[0] in ('Class', 'Mod') else ''

    refs = set()
    for v in pool.values():
        if v[0] == 'Ref':
            cname = cls(v[2])
            nat = pool.get(v[3])
            if nat and nat[0] == 'NAT':
                refs.add((cname, utf8(nat[1])))

    idx += 2  # access_flags
    this_cls = cls(struct.unpack('>H', data[idx:idx+2])[0]); idx += 2
    idx += 2  # super_class
    ic = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2 + 2 * ic
    declared = set()
    for section in ('fields', 'methods'):
        mc = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
        for _ in range(mc):
            idx += 2  # access
            name_i, desc_i = struct.unpack('>HH', data[idx:idx+4]); idx += 4
            if section == 'methods':
                declared.add(utf8(name_i))
            ac = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
            for _ in range(ac):
                idx += 2  # attribute_name_index
                alen = struct.unpack('>I', data[idx:idx+4])[0]; idx += 4
                idx += alen
    return this_cls, declared, refs


def collect_jars(ide_dir):
    jars = []
    for sub in ('lib', 'lib/modules', 'lib/ext'):
        jars += glob.glob(os.path.join(ide_dir, sub, '*.jar'))
    for pdir in glob.glob(os.path.join(ide_dir, 'plugins', '*')):
        for sub in ('lib', 'lib/modules'):
            jars += glob.glob(os.path.join(pdir, sub, '*.jar'))
    return jars


def main():
    plugin_jar, ide_dir = sys.argv[1], sys.argv[2]
    refs = set()
    bad = []
    with zipfile.ZipFile(plugin_jar) as z:
        for name in z.namelist():
            if name.endswith('.class'):
                try:
                    _, _, r = parse_class(z.read(name))
                    refs.update(r)
                except Exception as e:
                    bad.append(f'{name}: {e!r}')
    if bad:
        print(f'⚠ 解析失败的类 ({len(bad)}):')
        for b in bad[:10]:
            print('  ' + b)
    com_refs = {r for r in refs if r[0].startswith(('com.intellij.', 'org.jetbrains.'))}
    print(f"插件引用平台方法/字段: {len(com_refs)} 个")

    platform_classes = set()
    platform_members = defaultdict(set)
    for j in collect_jars(ide_dir):
        try:
            with zipfile.ZipFile(j) as z:
                for name in z.namelist():
                    if not name.endswith('.class'):
                        continue
                    try:
                        cls_name, declared, r = parse_class(z.read(name))
                        if cls_name:
                            platform_classes.add(cls_name)
                            platform_members[cls_name].update(declared)
                        for owner, meth in r:
                            platform_members[owner].add(meth)
                    except Exception:
                        platform_classes.add(name[:-6].replace('/', '.'))
        except Exception:
            pass
    print(f"平台类: {len(platform_classes)} 个")

    missing = defaultdict(set)
    for owner, meth in com_refs:
        if owner not in platform_classes and owner not in platform_members:
            missing[owner].add(f'{meth}   ← 整个类缺失')
        elif meth not in platform_members.get(owner, set()):
            missing[owner].add(meth)
    if not missing:
        print('\n✅ 262 平台未发现缺失引用')
    else:
        print('\n❌ 262 平台缺失的引用:')
        for owner in sorted(missing):
            for m in sorted(missing[owner]):
                print(f'  {owner} :: {m}')


if __name__ == '__main__':
    main()
