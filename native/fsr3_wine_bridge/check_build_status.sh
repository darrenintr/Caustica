#!/bin/bash
# FSR3 Workflow Status Checker

echo "🔍 正在检查 GitHub Actions 构建状态..."
echo ""

# 等待几秒让 GitHub 处理推送
sleep 5

# 获取最新的 workflow 运行
echo "📊 最新的 workflow 运行："
gh run list --repo darrenintr/Caustica --limit 5 --json databaseId,status,conclusion,name,createdAt | \
  jq -r '.[] | "\(.name): \(.status) - \(.conclusion // "running")"'

echo ""
echo "🔗 查看详细信息："
echo "https://github.com/darrenintr/Caustica/actions"
echo ""

# 检查最新的 FSR3 相关构建
echo "🎯 FSR3 相关构建："
gh run list --repo darrenintr/Caustica --limit 10 | grep -i "fsr3\|simplified" | head -5

echo ""
echo "💡 提示："
echo "  - ✅ 绿色对号 = 成功"
echo "  - ❌ 红色 X = 失败"
echo "  - 🟡 黄色圆圈 = 进行中"
echo ""
echo "等待构建完成后，你可以再次运行此脚本检查结果。"
