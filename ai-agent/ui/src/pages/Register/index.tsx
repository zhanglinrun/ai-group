import { memo, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button, Card, Form, Input, Typography } from 'antd';
import { authApi } from '@/services/auth';
import { ROUTES } from '@/router/routes';
import { isAuthenticated } from '@/auth/token';

type RegisterFormValues = {
  username: string;
  password: string;
  email?: string;
  phone?: string;
};

const RegisterPage = memo(() => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (isAuthenticated()) {
      navigate(ROUTES.CHAT, { replace: true });
    }
  }, [navigate]);

  const handleSubmit = async (values: RegisterFormValues) => {
    setLoading(true);
    try {
      await authApi.register(values);
      const loginResponse = await authApi.login({
        username: values.username,
        password: values.password,
      });
      authApi.persistLogin(loginResponse);
      navigate(ROUTES.PRICING, { replace: true });
    } catch (error) {
      console.error('注册失败', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--page-gradient)] px-4">
      <Card className="w-full max-w-md shadow-lg">
        <Typography.Title level={3} className="!mb-2">
          注册
        </Typography.Title>
        <Typography.Paragraph type="secondary" className="!mb-6">
          注册即享每月 5 点免费额度，未使用部分月底清零
        </Typography.Paragraph>
        <Form layout="vertical" onFinish={handleSubmit} autoComplete="off">
          <Form.Item
            label="用户名"
            name="username"
            rules={[
              { required: true, message: '请输入用户名' },
              { min: 3, message: '用户名至少 3 个字符' },
            ]}
          >
            <Input placeholder="3-50 个字符" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 6, message: '密码至少 6 个字符' },
            ]}
          >
            <Input.Password placeholder="6-64 个字符" />
          </Form.Item>
          <Form.Item label="邮箱（可选）" name="email">
            <Input placeholder="name@example.com" />
          </Form.Item>
          <Form.Item label="手机号（可选）" name="phone">
            <Input placeholder="11 位手机号" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            注册并登录
          </Button>
        </Form>
        <div className="mt-4 text-center text-sm text-[var(--chat-text-soft)]">
          已有账号？{' '}
          <Link to={ROUTES.LOGIN} className="text-[var(--primary)]">
            去登录
          </Link>
        </div>
      </Card>
    </div>
  );
});

RegisterPage.displayName = 'RegisterPage';

export default RegisterPage;
