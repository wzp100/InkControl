# 配置校验：由 common.sh 的 ic_validate 调用。
#   awk -F= -v retired_file=<lib/retired.txt> -f validate.awk <lib/features.tsv> <configuration.txt>
# 退出码 0 表示配置合法。规则全部来自功能目录导出的两张表，这里不手写任何功能名单：
#   features.tsv  ID、候选值（含 @curve/@number 等类型记号）、依赖、是否基线键、默认是否开启
#   retired.txt   已下线的 ID
# 只做格式与取值校验，不执行、不 source 配置内容。

function curve(choices, value,   n, parts, i) {
  if (index("," choices ",", ",@curve,")==0 || substr(value,1,6)!="curve:") return 0
  n=split(substr(value,7), parts, ",")
  if (n!=9) return 0
  for (i=1; i<=n; i++) if (parts[i]!~/^[0-9]+$/ || length(parts[i])>4 || parts[i]+0>2000) return 0
  return 1
}
function number(choices, value,   n) {
  if (index("," choices ",", ",@number,")==0 || value!~/^[0-9]+$/) return 0
  n=value+0
  if (length(value)>3 || n<1 || n>999) return 0
  return 1
}
function pkglist(list,   n, parts, j) {
  if (list=="") return 1
  n=split(list, parts, ",")
  if (n>200) return 0
  for (j=1; j<=n; j++) if (parts[j]!~/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$/ || length(parts[j])>255) return 0
  return 1
}
function apps(choices, value,   mode, semi, second, white, black) {
  if (index("," choices ",", ",@apps,")==0) return 0
  if (value=="") return 1
  semi=index(value, ";")
  if (semi==0) {
    second=index(value, ":")
    if (second>0) {
      mode=substr(value, 1, second-1)
      if (mode!="whitelist" && mode!="blacklist") return 0
      return pkglist(substr(value, second+1))
    }
    return (value=="whitelist" || value=="blacklist")
  }
  mode=substr(value, 1, semi-1)
  if (mode!="whitelist" && mode!="blacklist") return 0
  second=index(substr(value, semi+1), ";")
  if (second==0) return 0
  second=semi+second
  if (index(substr(value, second+1), ";")>0) return 0
  white=substr(value, semi+1, second-semi-1)
  black=substr(value, second+1)
  return pkglist(white) && pkglist(black)
}
function countdowns(choices, value,   n, parts, i, d, y, m, dd, dim, step) {        if (index("," choices ",", ",@countdowns,")==0) return 0
  if (value=="") return 1
  n=split(value, parts, ";")
  # 新格式 12 段「事项;日期;农历;显示」×3；旧格式 6 段「事项;日期」×3（农历=否、显示=是）
  if (n!=12 && n!=6) return 0
  step=(n==12)?4:2
  for (i=1; i<=n; i+=step) {
    if (length(parts[i])>48) return 0
    d=parts[i+1]
    if (d!="") {
      if (d !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/) return 0
      y=substr(d,1,4)+0; m=substr(d,6,2)+0; dd=substr(d,9,2)+0
      if (m<1 || m>12 || dd<1) return 0
      if (step==4 && parts[i+2]=="1") {
        if (dd>30) return 0   # 农历月最大 30 天；大小月/闰月语义由 App 侧完整校验
      } else {
        dim=(m==4||m==6||m==9||m==11)?30:(m==2?((y%4==0&&(y%100!=0||y%400==0))?29:28):31)
        if (dd>dim) return 0
      }
    }
    if (step==4 && parts[i+2]!="0" && parts[i+2]!="1") return 0
    if (step==4 && parts[i+3]!="0" && parts[i+3]!="1") return 0
  }
  return 1
}
function pkgone(choices, value) {
  # 单个应用包名（「截屏便签」那一格换成的应用）
  if (index("," choices ",", ",@package,")==0) return 0
  if (value=="") return 1
  return pkglist(value)
}
function pkgs(choices, value) {
  # 纯包名逗号列表（「目标阅读器」），不带白／黑名单模式
  if (index("," choices ",", ",@packages,")==0) return 0
  if (value=="") return 1
  return pkglist(value)
}
function fontpath(value,   n) {
  # 字体目录或字体名：绝对路径/文件名，不含等号、分号、控制字符与 shell 元字符
  if (value=="") return 1
  if (length(value)>200) return 0
  if (value ~ /[=;$`"|&<>*?!]/) return 0
  return 1
}
function freeform(key, choices, legacy, value) {
  # 自由文本只对明确声明的项放行，其余选项仍必须命中候选值：
  # @path/@font（字体目录与字体名）、@panel（值恒为空）、已下线的旧键，
  # 以及旧配置里 lock.notify 的 ext（App 侧会迁成 lock.ext=1）。
  if (key=="lock.notify") return value=="ext"
  if (index("," choices ",", ",@panel,")!=0) return value==""
  if (!legacy && index("," choices ",", ",@path,")==0 && index("," choices ",", ",@font,")==0) return 0
  return fontpath(value)
}
# 一个取值合法，当且仅当它在候选值里，或符合该项声明的某种类型。
function accepted(key, choices, legacy, value) {
  if (index("," choices ",", "," value ",") != 0) return 1
  if (curve(choices, value) || number(choices, value)) return 1
  if (apps(choices, value) || pkgs(choices, value) || pkgone(choices, value)) return 1
  if (countdowns(choices, value)) return 1
  return freeform(key, choices, legacy, value)
}
# 已下线的键由功能目录导出（lib/retired.txt），与 App 的解析共用同一张表。
BEGIN {
  if (retired_file != "") {
    while ((getline line < retired_file) > 0) if (line != "") retired[line] = 1
    close(retired_file)
  }
}
# features.tsv：ID、候选值、依赖、是否基线键（必须成对出现）、默认是否开启。
NR==FNR {split($0,s,"\t"); choices[s[1]]=s[2]; known[s[1]]=1; baseline[s[1]]=(s[4]=="1"); n++; next}
{if (NF!=2 || seen[$1]++) bad=1}
$1=="version" {if($2!="1") bad=1; next}
$1=="revision" {if($2!~/^[0-9]+$/ || length($2)>15) bad=1; next}
# 其余每一行是 <ID>=<0|1> 或 <ID>.value=<取值>。
# 目录里没有的 ID 一律拒绝，只有已下线的键（retired）例外：旧配置升级后可能还留着，容忍即可。
{
  key = $1
  is_value = sub(/\.value$/, "", key)
  legacy = (key in retired)
  if (!known[key] && !legacy) { bad = 1; next }
  if (!is_value) { if ($2 != "0" && $2 != "1") bad = 1; next }
  # 类型记号本身不是合法取值。
  if ($2 ~ /^@(curve|number|apps|packages|countdowns|path|font|panel|package)$/) { bad = 1; next }
  if (!accepted(key, choices[key], legacy, $2)) bad = 1
}
END {
  if(!seen["version"] || !seen["revision"]) bad=1
  for(key in known) {
    # 基线键（首个公开版本就有的）必须成对出现；之后新增的功能整对缺省时按默认处理，
    # 只写了一半仍然是损坏配置。哪些是基线键由功能目录导出，不在这里手写名单。
    optional=!baseline[key]
    if(optional && !seen[key] && !seen[key ".value"]) continue
    if(!seen[key] || !seen[key ".value"]) bad=1
  }
  exit bad
}
