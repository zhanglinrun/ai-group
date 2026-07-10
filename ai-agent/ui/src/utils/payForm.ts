/** Submit Alipay page-pay HTML form in a new window (agent-group style). */
export function submitAlipayForm(html: string): void {
  const trimmed = html.trim();
  if (!trimmed) {
    throw new Error('支付表单无效');
  }

  const payWindow = window.open('', '_blank');
  if (payWindow && !payWindow.closed) {
    const page = `<!doctype html><html><head><meta charset="UTF-8"><title>支付宝支付</title></head><body>${trimmed}<script>window.opener=null;var form=document.forms[0];if(form){form.submit();}</script></body></html>`;
    payWindow.document.open();
    payWindow.document.write(page);
    payWindow.document.close();
    return;
  }

  const container = document.createElement('div');
  container.hidden = true;
  container.innerHTML = trimmed;
  const form = container.querySelector('form');
  if (!form) {
    throw new Error('支付表单无效');
  }
  form.target = '_blank';
  document.body.appendChild(container);
  form.submit();
  window.setTimeout(() => container.remove(), 1000);
}

export function paymentReturnUrl(): string {
  if (typeof window === 'undefined') {
    return '';
  }
  const url = new URL(`${window.location.origin}/pricing`);
  url.searchParams.set('tab', 'orders');
  url.searchParams.set('paymentReturn', '1');
  return url.toString();
}
