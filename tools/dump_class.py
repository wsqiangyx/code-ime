"""解析 class 文件：列出接口、方法（含 flags 与注解）。用于核查平台 API 形态。

用法: python tools/dump_class.py <jar或目录> <class内部名>
  jar 可以是文件或 IDE 安装目录（自动搜索所有 jar）。
"""
import sys, os, glob, struct, re, zipfile


def parse(data):
    pool = {}
    idx = 8
    n = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    i = 1
    while i < n:
        tag = data[idx]
        idx += 1
        if tag == 7:
            pool[i] = ('C', struct.unpack('>H', data[idx:idx + 2])[0])
            idx += 2
        elif tag in (9, 10, 11):
            pool[i] = ('R', struct.unpack('>HH', data[idx:idx + 4]))
            idx += 4
        elif tag == 8:
            pool[i] = ('S', struct.unpack('>H', data[idx:idx + 2])[0])
            idx += 2
        elif tag == 15:
            idx += 3
        elif tag == 16:
            idx += 2
        elif tag in (17, 18):
            idx += 4
        elif tag in (19, 20):
            pool[i] = ('C', struct.unpack('>H', data[idx:idx + 2])[0])
            idx += 2
        elif tag in (3, 4):
            idx += 4
        elif tag in (5, 6):
            idx += 8
            i += 1
        elif tag == 1:
            ln = struct.unpack('>H', data[idx:idx + 2])[0]
            idx += 2
            pool[i] = ('U', data[idx:idx + ln])
            idx += ln
        elif tag == 12:
            pool[i] = ('N', struct.unpack('>HH', data[idx:idx + 4]))
            idx += 4
        else:
            raise ValueError(f'未知常量池 tag {tag} @ entry {i} offset {idx - 1}')
        i += 1

    def utf8(k):
        e = pool.get(k)
        return e[1].decode('utf-8', 'replace') if e and e[0] == 'U' else ''

    def clsname(k):
        e = pool.get(k)
        return utf8(e[1]) if e and e[0] == 'C' else ''

    def read_attrs(pos):
        ac = struct.unpack('>H', data[pos:pos + 2])[0]
        pos += 2
        out = []
        for _ in range(ac):
            ai = struct.unpack('>H', data[pos:pos + 2])[0]
            alen = struct.unpack('>I', data[pos + 2:pos + 6])[0]
            pos += 6
            out.append((utf8(ai), data[pos:pos + alen]))
            pos += alen
        return pos, out

    # read_attrs 返回的属性名列表里再补一个标记：Java 专用 Deprecated 属性
    # （在调用处用 'Deprecated' 属性名检测）

    idx += 2  # access_flags
    idx += 2  # this_class
    idx += 2  # super_class
    ic = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    ifc = [clsname(struct.unpack('>H', data[idx + 2 * k:idx + 2 * k + 2])[0]) for k in range(ic)]
    idx += 2 * ic

    fc = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    for _ in range(fc):
        idx += 6
        idx, _ = read_attrs(idx)

    methods = []
    mc = struct.unpack('>H', data[idx:idx + 2])[0]
    idx += 2
    for _ in range(mc):
        flags = struct.unpack('>H', data[idx:idx + 2])[0]
        idx += 2
        name = utf8(struct.unpack('>H', data[idx:idx + 2])[0])
        idx += 2
        desc = utf8(struct.unpack('>H', data[idx:idx + 2])[0])
        idx += 2
        idx, attrs = read_attrs(idx)
        txt = ' '.join(d.decode('latin-1') for a, d in attrs if 'Annotations' in a)
        anns = [a.replace('/', '.') for a in re.findall(r'L([A-Za-z0-9/$]+);', txt)]
        attr_names = [a for a, _d in attrs]
        if 'Deprecated' in attr_names:  # Java 专用 Deprecated 属性（javac 生成）
            anns.append('java.Deprecated-attr')
        methods.append((name, desc, flags, anns))
    return ifc, methods


def locate(base, cn):
    if os.path.isfile(base) and base.endswith('.jar'):
        with zipfile.ZipFile(base) as z:
            return base, z.read(cn)
    jars = glob.glob(os.path.join(base, 'lib', '*.jar'))
    jars += glob.glob(os.path.join(base, 'lib', 'modules', '*.jar'))
    for pdir in glob.glob(os.path.join(base, 'plugins', '*')):
        jars += glob.glob(os.path.join(pdir, 'lib', '*.jar'))
        jars += glob.glob(os.path.join(pdir, 'lib', 'modules', '*.jar'))
    for j in jars:
        try:
            with zipfile.ZipFile(j) as z:
                if cn in z.namelist():
                    return j, z.read(cn)
        except Exception:
            pass
    return None, None


def main():
    base, cn = sys.argv[1], sys.argv[2]
    jar, data = locate(base, cn)
    if not data:
        print('未找到', cn)
        return
    print(f'# {cn}  <- {os.path.basename(jar)}')
    ifc, methods = parse(data)
    print('implements:', ', '.join(ifc))
    for name, desc, flags, anns in methods:
        st = []
        if flags & 0x0040: st.append('BRIDGE')
        if flags & 0x1000: st.append('SYNTH')
        if flags & 0x0008: st.append('static')
        if flags & 0x0400: st.append('abstract')
        if flags & 0x0001: st.append('public')
        dep = [a for a in anns if a.endswith('.Deprecated') or a == 'java.Deprecated-attr']
        api = [a for a in anns if 'ApiStatus' in a]
        print(f"  {'|'.join(st) or '-'}  {name}  {desc}")
        if dep:
            print('      @Deprecated')
        if api:
            print(f'      {api}')


if __name__ == '__main__':
    main()
