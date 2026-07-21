import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';
import eslintConfigPrettier from 'eslint-config-prettier';

export default tseslint.config(
  {
    ignores: ['dist', 'build', 'coverage', 'node_modules', '*.min.js'],
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: {
        ...globals.browser,
        REQUEST_BASE_URL: 'readonly',
        env: 'readonly',
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // 代码风格（分号/缩进/换行等）统一交给 Prettier（见 .prettierrc.json）；
      // ESLint 只保留正确性规则，避免与 Prettier 规则互相打架、产生大量非语义报错。
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          // 通过 rest 解构有意剔除的字段（如 {id, ...rest}）不算未使用
          ignoreRestSiblings: true,
        },
      ],
    },
  },
  // 必须放在最后：关闭一切与 Prettier 冲突的样式类规则
  eslintConfigPrettier,
);
