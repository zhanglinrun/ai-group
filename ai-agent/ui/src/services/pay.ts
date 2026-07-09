import api from "./index";

export interface CreatePayOrderRequest {
  userId: string;
  productId: string;
  productCode: string;
  teamId?: string;
  activityId?: number;
  marketType?: number;
}

export const payApi = {
  createOrder: (payload: CreatePayOrderRequest) =>
    api.post<string>("/api/v1/alipay/create_pay_order", payload) as unknown as Promise<string>,
};
