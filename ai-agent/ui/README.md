# Reactor UI

Reactor UI 是一个基于 React、TypeScript 和 Vite 的现代化项目。

## 功能特性

- 基于 React 19 和 TypeScript
- 使用 Vite 作为构建工具，提供快速的开发体验
- 集成 Ant Design 组件库
- 支持 Markdown 渲染
- 文件预览和处理功能
- 使用 ESLint 和 Prettier 进行代码规范化

## 快速开始

1. 使用 pnpm 安装依赖：

```powershell
$ErrorActionPreference = 'Stop'
pnpm install --frozen-lockfile
```

2. 启动开发服务器：

```powershell
$ErrorActionPreference = 'Stop'
pnpm run dev
```

3. 在浏览器中打开 http://127.0.0.1:5173 查看应用。开发服务器通过 Gateway 访问后端，
   不应把 `AI_GROUP_INTERNAL_TOKEN` 或 reactor-tool 内部令牌写入浏览器环境变量。

## 可用脚本

- `pnpm dev`: 启动开发服务器
- `pnpm build`: 构建生产版本
- `pnpm lint`: 运行 ESLint 检查
- `pnpm fix`: 自动修复 ESLint 问题
- `pnpm preview`: 预览生产构建

## 贡献

我们欢迎所有形式的贡献！请查看 [CONTRIBUTING.md](CONTRIBUTING.md) 了解如何开始。

## 项目边界

这是 AI-Group 的前端部分，许可与发布策略以仓库根目录说明为准；当前目录没有单独的开源许可证文件。
