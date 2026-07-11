import '@ant-design/v5-patch-for-react-19';
// import React, { StrictMode } from 'react'; // 暂时移除严格模式
import { createRoot } from 'react-dom/client';
import App from './App';
import { bootstrapTheme } from './theme';
import './global.css';

// 在首帧渲染前同步应用持久化主题，避免深色/换肤用户出现浅色闪烁
bootstrapTheme();

const root = document.getElementById('root');

if (root) {
  createRoot(root).render(<App />);
} else {
  console.error('Root element not found');
}
