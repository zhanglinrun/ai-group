import '@ant-design/v5-patch-for-react-19';
// import React, { StrictMode } from 'react'; // 暂时移除严格模式
import { createRoot } from 'react-dom/client';
import App from './App';
// 入口只依赖首屏主题引导逻辑，避免通过 barrel 文件提前加载设置页和 Ant Design 主题组件。
import { bootstrapTheme } from './theme/themeRuntime';
import './global.css';

// 在首帧渲染前同步应用持久化主题，避免深色/换肤用户出现浅色闪烁
bootstrapTheme();

const root = document.getElementById('root');

if (root) {
  createRoot(root).render(<App />);
} else {
  console.error('Root element not found');
}
