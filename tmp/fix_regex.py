import io
p = r"android/app/src/main/java/io/github/rr5201314/liteschedule/data/ZfClient.kt"
lines = io.open(p, encoding="utf-8").read().split("\n")
new_line = '            val block = Regex("<select name=\\"$name\\".*?</select>", RegexOption.DOT_MATCHES_ALL).find(page)?.value ?: return emptyList()'
done = False
for idx, l in enumerate(lines):
    if "select name=" in l and "Regex(" in l:
        lines[idx] = new_line
        done = True
        print("改写第", idx + 1, "行")
        break
if not done:
    print("未找到目标行")
io.open(p, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
