import io, re, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
with io.open('.tmp/ui_now.xml', 'r', encoding='utf-8', errors='replace') as f:
    data = f.read()
m = re.search(r'<\?xml', data)
if m:
    data = data[m.start():]
nodes = re.findall(r'<node[^>]*?>', data)
parsed = []
for n in nodes:
    b = re.search(r'bounds="([^"]+)"', n)
    t = re.search(r' text="([^"]*)"', n)
    d = re.search(r' content-desc="([^"]*)"', n)
    cls = re.search(r' class="([^"]+)"', n)
    clickable = 'clickable="true"' in n
    s = (t.group(1) if t else '') or (d.group(1) if d else '')
    if not b:
        continue
    nums = list(map(int, re.findall(r'\d+', b.group(1))))
    if len(nums) >= 4:
        c = cls.group(1).split('.')[-1] if cls else '?'
        parsed.append((nums[1], nums[0], nums[3], nums[2], c, clickable, s[:60]))
parsed.sort()
for y0, x0, y1, x1, c, clk, s in parsed:
    flag = '*' if clk else ' '
    label = s if s.strip() else '<empty>'
    print(f'{flag}[{x0},{y0}][{x1},{y1}]  {c:30}  {label}')
