#!/usr/bin/env python3
# ============================================================
#  生成发布包用的种子词库（seed-words.json）
#
#  用法:
#    python3 tools/make-seed.py <输入.json> <输出.json>
#
#  输入是「数据管理」导出的 JSON 备份，输出是给发布包用的种子文件：
#    · 清空所有复习记录（复习次数、正确率、拼写历史、最后复习时间）
#    · 保留词库内容（生词/音标/含义/例句/例句释义/同义标签）
#    · 保留词组树和每个单词的词组归属
#
#  为什么不直接用备份本身：备份里带着复习记录，等于把使用痕迹一起发给别人。
#  种子只负责「让对方有一份能用的词库」，复习记录从零开始。
#
#  备份里的 ai_config / proxy_config 本来就不导出（见 DataExportImportService.exportAll），
#  所以这里也不用额外处理，API Key 和代理地址不会外泄。
# ============================================================
import json
import sys

# 要清空的复习相关字段
REVIEW_FIELDS = [
    "lastReviewDate",
    "reviewTotal",
    "recogCorrect",
    "recogWrong",
    "spellCorrect",
    "spellWrong",
    "consecutiveCorrect",
    "isDormant",
    "spellRecentHistory",
    "recentSpellWrong",
    "consecutiveSpellWrong",
]

# 清空后各字段的默认值（对应 Word 实体的字段初始值）
CLEARED_DEFAULTS = {
    "lastReviewDate": None,
    "reviewTotal": 0,
    "recogCorrect": 0,
    "recogWrong": 0,
    "spellCorrect": 0,
    "spellWrong": 0,
    "consecutiveCorrect": 0,
    "isDormant": False,
    "spellRecentHistory": "",
    "recentSpellWrong": 0,
    "consecutiveSpellWrong": 0,
}


def make_seed(data):
    seed = {
        "version": data.get("version", 2),
        "exportTime": data.get("exportTime"),
        "seedNote": "词库种子数据：不含复习记录，导入后从零开始复习",
    }

    # 复习配置和易错词阈值是纯数字、无隐私，用户调好了就该跟着走
    for key in ("reviewConfig", "difficultConfig"):
        if key in data:
            seed[key] = data[key]

    # 词组树：完整保留（含 isDefault / isDifficult 标记和父子层级）
    seed["groups"] = data.get("groups", [])

    # 单词：清空复习字段，保留内容和词组归属
    words = []
    for w in data.get("words", []):
        new = dict(w)
        for field in REVIEW_FIELDS:
            if field in new:
                new[field] = CLEARED_DEFAULTS[field]
        words.append(new)
    seed["words"] = words

    return seed


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)

    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8") as f:
        data = json.load(f)

    if "words" not in data:
        print("❌ %s 不像是一份备份文件（没有 words 字段）" % src)
        sys.exit(1)

    seed = make_seed(data)
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(seed, f, ensure_ascii=False, indent=1)

    print("   ✅ 种子文件已生成: %s" % dst)
    print("      词组 %d 个，单词 %d 个（复习记录已清空）"
          % (len(seed["groups"]), len(seed["words"])))


if __name__ == "__main__":
    main()
