import React, { useEffect } from 'react';
import { ConfigProvider, message } from 'antd';
import { RouterProvider } from 'react-router-dom';
import zhCN from 'antd/locale/zh_CN';
import router from './router';
import { setMessage } from '@/utils';

// App 组件：应用的根组件，设置全局配置和路由
const App: ReactorType.FC = React.memo(() => {
  // 在根组件初始化全局 message，确保 Login/Register 等 Layout 之外的页面也能弹出提示
  const [messageApi, messageContent] = message.useMessage();

  useEffect(() => {
    setMessage(messageApi);
  }, [messageApi]);

  return (
    <ConfigProvider locale={zhCN}>
      {messageContent}
      <RouterProvider router={router} />
    </ConfigProvider>
  );
});

export default App;
