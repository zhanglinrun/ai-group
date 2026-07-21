import { memo, useEffect } from 'react';
import { Outlet } from 'react-router-dom';
import { ConfigProvider, message } from 'antd';
import { ConstantProvider } from '@/hooks';
import * as constants from '@/utils/constants';
import { setMessage } from '@/utils';

// Layout 组件：应用的主要布局结构
const Layout: ReactorType.FC = memo(() => {
  const [messageApi, messageContent] = message.useMessage();

  useEffect(() => {
    // 初始化全局 message
    setMessage(messageApi);
  }, [messageApi]);

  // 主题由根部 ThemeProvider 统一提供，这里的 ConfigProvider 仅保留 message 挂载；
  // 不再覆写 theme，令其继承全局调色板。
  return (
    <ConfigProvider>
      {messageContent}
      {/* 暂时只有静态的 */}
      <ConstantProvider value={constants}>
        <Outlet />
      </ConstantProvider>
    </ConfigProvider>
  );
});

Layout.displayName = 'Layout';

export default Layout;
