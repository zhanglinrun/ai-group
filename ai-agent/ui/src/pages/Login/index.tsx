import { memo, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Button, Card, Form, Input, Typography } from 'antd';
import { authApi } from '@/services/auth';
import { ROUTES } from '@/router/routes';
import { isAuthenticated } from '@/auth/token';
import { showMessage } from '@/utils';

type LoginFormValues = {
  username: string;
  password: string;
};

const LoginPage = memo(() => {
  const navigate = useNavigate();
  const location = useLocation();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isAuthenticated()) {
      return;
    }
    const redirectTo = (location.state as { from?: string } | null)?.from || ROUTES.CHAT;
    navigate(redirectTo, { replace: true });
  }, [location.state, navigate]);

  if (isAuthenticated()) {
    return null;
  }

  const handleSubmit = async (values: LoginFormValues) => {
    setLoading(true);
    try {
      const response = await authApi.login(values);
      authApi.persistLogin(response);
      const redirectTo = (location.state as { from?: string } | null)?.from || ROUTES.CHAT;
      navigate(redirectTo, { replace: true });
    } catch (error) {
      console.error('登录失败', error);
      const msg =
        error instanceof Error && error.message ? error.message : '登录失败，请检查用户名或密码';
      showMessage()?.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--page-gradient)] px-4">
      <Card className="w-full max-w-md shadow-lg">
        <Typography.Title level={3} className="!mb-2">
          登录
        </Typography.Title>
        <Typography.Paragraph type="secondary" className="!mb-6">
          使用账号登录后购买额度并使用 AI 对话能力
        </Typography.Paragraph>
        <Form layout="vertical" onFinish={handleSubmit} autoComplete="off">
          <Form.Item
            label="用户名"
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input placeholder="请输入用户名" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password placeholder="请输入密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登录
          </Button>
        </Form>
        <div className="mt-4 text-center text-sm text-[var(--chat-text-soft)]">
          还没有账号？{' '}
          <Link to={ROUTES.REGISTER} className="text-[var(--primary)]">
            立即注册
          </Link>
        </div>
      </Card>
    </div>
  );
});

LoginPage.displayName = 'LoginPage';

export default LoginPage;
