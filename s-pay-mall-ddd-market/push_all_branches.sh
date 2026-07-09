#!/bin/bash

# 获取当前工程的根目录名称作为仓库名称
PROJECT_NAME=$(basename $(git rev-parse --show-toplevel))

# 构建完整的远程仓库 URL
REMOTE_URL="https://github.com/xfg-studio-project/${PROJECT_NAME}.git"

# 检查远程仓库是否已存在，如果没有则添加
if ! git remote | grep -q '^target$'; then
  git remote add target $REMOTE_URL
fi

# 获取最新的远程仓库信息
git fetch target

# 循环遍历所有本地分支并推送到远程
for branch in $(git for-each-ref --format='%(refname:short)' refs/heads/); do
  echo "正在推送分支 $branch 到远程仓库 $REMOTE_URL"
  git push target $branch
done

echo "所有分支已推送完成！"
