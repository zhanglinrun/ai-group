package com.aigroup.paymall.trigger.http;

import com.aigroup.paymall.api.dto.CreatePayQrResponseDTO;
import com.aigroup.paymall.api.dto.CreatePayRequestDTO;
import com.aigroup.paymall.api.dto.NotifyRequestDTO;
import com.aigroup.paymall.api.dto.QueryOrderListRequestDTO;
import com.aigroup.paymall.api.dto.QueryOrderListResponseDTO;
import com.aigroup.paymall.api.dto.RefundOrderRequestDTO;
import com.aigroup.paymall.api.dto.RefundOrderResponseDTO;
import com.aigroup.paymall.api.response.Response;
import com.aigroup.paymall.domain.order.model.entity.OrderEntity;
import com.aigroup.paymall.domain.order.model.entity.PayOrderEntity;
import com.aigroup.paymall.domain.order.model.entity.ShopCartEntity;
import com.aigroup.paymall.domain.order.model.valobj.MarketTypeVO;
import com.aigroup.paymall.domain.order.model.valobj.OrderStatusVO;
import com.aigroup.paymall.domain.order.service.IOrderService;
import com.aigroup.paymall.trigger.http.support.GatewayUserResolver;
import com.aigroup.paymall.trigger.http.support.InternalCallbackAuthSupport;
import com.aigroup.paymall.types.common.Constants;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/alipay/")
public class AliPayController {

    @Value("${alipay.alipay_public_key}")
    private String alipayPublicKey;

    @Resource
    private IOrderService orderService;
    
    @Resource
    private AlipayClient alipayClient;

    @Resource
    private InternalCallbackAuthSupport internalCallbackAuthSupport;

    @Resource
    private GatewayUserResolver gatewayUserResolver;

    @Resource
    private Environment environment;

    @Value("${ai-group.pay.demo-complete-enabled:false}")
    private boolean demoCompleteEnabled;

    /**
     * http://localhost:8080/api/v1/alipay/create_pay_order
     * <p>
     * {
     * "userId": "10001",
     * "productId": "100001"
     * }
     */
    @RequestMapping(value = "create_pay_order", method = RequestMethod.POST)
    public Response<String> createPayOrder(@RequestBody CreatePayRequestDTO createPayRequestDTO,
                                           HttpServletRequest servletRequest) {
        try {
            String userId = gatewayUserResolver.resolveUserId(servletRequest, createPayRequestDTO.getUserId());
            log.info("创建支付订单请求，userId:{} productId:{}", userId, createPayRequestDTO.getProductId());
            String productId = createPayRequestDTO.getProductId();
            String teamId = createPayRequestDTO.getTeamId();
            Integer marketType = createPayRequestDTO.getMarketType();
            String productCode = createPayRequestDTO.getProductCode();

            // 创建订单
            PayOrderEntity payOrderEntity = orderService.createOrder(ShopCartEntity.builder()
                    .userId(userId)
                    .productId(productId)
                    .productCode(productCode)
                    .teamId(teamId)
                    .marketTypeVO(MarketTypeVO.valueOf(marketType))
                    .activityId(createPayRequestDTO.getActivityId())
                    .build());

            log.info("支付订单创建成功，userId:{} productId:{} orderId:{}", userId, productId, payOrderEntity.getOrderId());
            return Response.<String>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(payOrderEntity.getPayUrl())
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("创建支付订单参数非法: {}", e.getMessage());
            return Response.<String>builder()
                    .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("创建支付订单失败，userId:{} productId:{}", createPayRequestDTO.getUserId(), createPayRequestDTO.getProductId(), e);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 当面付/扫码支付下单：创建订单（拼团模式会锁单）后返回 qr_code 供前端渲染二维码。
     * 支付结果仍由 alipay_notify_url（异步）/ sync_settle（前端轮询）驱动，金额按 pay_amount 校验。
     */
    @RequestMapping(value = "create_pay_qrcode", method = RequestMethod.POST)
    public Response<CreatePayQrResponseDTO> createPayQrCode(@RequestBody CreatePayRequestDTO createPayRequestDTO,
                                                            HttpServletRequest servletRequest) {
        try {
            String userId = gatewayUserResolver.resolveUserId(servletRequest, createPayRequestDTO.getUserId());
            String productId = createPayRequestDTO.getProductId();
            String teamId = createPayRequestDTO.getTeamId();
            Integer marketType = createPayRequestDTO.getMarketType();
            String productCode = createPayRequestDTO.getProductCode();

            PayOrderEntity payOrderEntity = orderService.createOrder(ShopCartEntity.builder()
                    .userId(userId)
                    .productId(productId)
                    .productCode(productCode)
                    .teamId(teamId)
                    .marketTypeVO(MarketTypeVO.valueOf(marketType))
                    .activityId(createPayRequestDTO.getActivityId())
                    .build());

            String qrCode = orderService.prepareTradeQrCode(payOrderEntity.getOrderId());
            OrderEntity persistedOrder = orderService.queryOrderByOrderId(payOrderEntity.getOrderId());

            return Response.<CreatePayQrResponseDTO>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(CreatePayQrResponseDTO.builder()
                             .orderId(payOrderEntity.getOrderId())
                             .qrCode(qrCode)
                             .amount(resolveDisplayAmount(persistedOrder))
                             .demoCompletionEnabled(isDemoCompletionAvailable())
                             .build())
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("create pay qrcode illegal param: {}", e.getMessage());
            return Response.<CreatePayQrResponseDTO>builder()
                    .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("create pay qrcode failed userId:{} productId:{}", createPayRequestDTO.getUserId(), createPayRequestDTO.getProductId(), e);
            return Response.<CreatePayQrResponseDTO>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "group_buy_notify", method = RequestMethod.POST)
    public String groupBuyNotify(@RequestBody NotifyRequestDTO requestDTO, HttpServletRequest servletRequest) {
        if (!internalCallbackAuthSupport.isAuthorized(servletRequest)) {
            log.warn("group buy notify rejected: missing or invalid internal token");
            return "error";
        }
        log.info("group buy notify settlement start {}", JSON.toJSONString(requestDTO));
        try {
            orderService.changeOrderMarketSettlement(requestDTO.getOutTradeNoList(), requestDTO.getBonusQuota());
            return "success";
        } catch (Exception e) {
            log.error("group buy notify settlement failed {}", JSON.toJSONString(requestDTO), e);
            return "error";
        }
    }

    /**
     * http://xfg-studio.natapp1.cc/api/v1/alipay/alipay_notify_url
     */
    @RequestMapping(value = "alipay_notify_url", method = RequestMethod.POST)
    public String payNotify(HttpServletRequest request) throws AlipayApiException, ParseException {
        log.info("收到支付宝异步通知，交易状态:{}", request.getParameter("trade_status"));

        if (!"TRADE_SUCCESS".equals(request.getParameter("trade_status"))) {
            return "false";
        }

        Map<String, String> params = new HashMap<>();
        Map<String, String[]> requestParams = request.getParameterMap();
        for (String name : requestParams.keySet()) {
            params.put(name, request.getParameter(name));
        }

        String tradeNo = params.get("out_trade_no");
        String gmtPayment = params.get("gmt_payment");
        String alipayTradeNo = params.get("trade_no");

        String sign = params.get("sign");
        String content = AlipaySignature.getSignCheckContentV1(params);
        boolean checkSignature = AlipaySignature.rsa256CheckContent(content, sign, alipayPublicKey, "UTF-8"); // 验证签名
        // 签名验证失败时拒绝通知
        if (!checkSignature) {
            return "false";
        }

        // 记录回调中的关键支付信息
        log.info("订单标题: {}", params.get("subject"));
        log.info("交易状态: {}", params.get("trade_status"));
        log.info("支付宝交易号: {}", params.get("trade_no"));
        log.info("商户订单号: {}", params.get("out_trade_no"));
        log.info("订单金额: {}", params.get("total_amount"));
        log.info("买家支付宝用户 ID: {}", params.get("buyer_id"));
        log.info("支付时间: {}", params.get("gmt_payment"));
        log.info("买家实付金额: {}", params.get("buyer_pay_amount"));
        log.info("开始处理支付成功订单: {}", tradeNo);

        OrderEntity order = orderService.queryOrderByOrderId(tradeNo);
        if (order == null) {
            log.warn("pay notify ignored: order not found orderId={}", tradeNo);
            return "false";
        }
        BigDecimal expected = order.getPayAmount();
        BigDecimal paid = null;
        try {
            paid = new BigDecimal(params.get("total_amount"));
        } catch (Exception ex) {
            log.warn("pay notify rejected: invalid total_amount orderId={} total_amount={}", tradeNo, params.get("total_amount"));
            return "false";
        }
        if (expected != null && paid.compareTo(expected) != 0) {
            log.warn("pay notify rejected: amount mismatch orderId={} expected={} paid={}", tradeNo, expected, paid);
            return "false";
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        orderService.changeOrderPaySuccess(tradeNo, dateFormat.parse(params.get("gmt_payment")));

        return "success";
    }

    /**
     * http://localhost:8080/api/v1/alipay/query_user_order_list
     * <p>
     * {
     * "userId": "10001",
     * "lastId": null,
     * "pageSize": 10
     * }
     */
    @RequestMapping(value = "query_user_order_list", method = RequestMethod.POST)
    public Response<QueryOrderListResponseDTO> queryUserOrderList(@RequestBody QueryOrderListRequestDTO requestDTO,
                                                                 HttpServletRequest servletRequest) {
        try {
            String userId = gatewayUserResolver.resolveUserId(servletRequest, requestDTO.getUserId());
            log.info("查询用户订单列表，userId:{} lastId:{} pageSize:{}", userId, requestDTO.getLastId(), requestDTO.getPageSize());

            Long lastId = requestDTO.getLastId();
            Integer pageSize = requestDTO.getPageSize();
            
            // 多查询一条记录，用于判断是否还有下一页
            List<OrderEntity> orderList = orderService.queryUserOrderList(userId, lastId, pageSize + 1);
            
            // 判断是否还有更多记录
            boolean hasMore = orderList.size() > pageSize;
            if (hasMore) {
                orderList = orderList.subList(0, pageSize);
            }
            
            // 转换为接口响应对象
            List<QueryOrderListResponseDTO.OrderInfo> orderInfoList = orderList.stream().map(order -> {
                QueryOrderListResponseDTO.OrderInfo orderInfo = new QueryOrderListResponseDTO.OrderInfo();
                orderInfo.setId(order.getId());
                orderInfo.setUserId(order.getUserId());
                orderInfo.setProductId(order.getProductId());
                orderInfo.setProductName(order.getProductName());
                orderInfo.setOrderId(order.getOrderId());
                orderInfo.setOrderTime(order.getOrderTime());
                orderInfo.setTotalAmount(order.getTotalAmount());
                orderInfo.setStatus(order.getOrderStatusVO() != null ? order.getOrderStatusVO().getCode() : null);
                // 只有待支付订单才回传收银台链接；CLOSE/已支付/退款中订单不返回 payUrl，
                // 防止用户从旧收银台对已取消订单继续付款
                boolean payable = order.getOrderStatusVO() != null
                        && OrderStatusVO.PAY_WAIT.getCode().equals(order.getOrderStatusVO().getCode());
                orderInfo.setPayUrl(payable ? order.getPayUrl() : null);
                orderInfo.setMarketType(order.getMarketType());
                orderInfo.setMarketDeductionAmount(order.getMarketDeductionAmount());
                orderInfo.setPayAmount(order.getPayAmount());
                orderInfo.setPayTime(order.getPayTime());
                return orderInfo;
            }).collect(Collectors.toList());
            
            QueryOrderListResponseDTO responseDTO = new QueryOrderListResponseDTO();
            responseDTO.setOrderList(orderInfoList);
            responseDTO.setHasMore(hasMore);
            responseDTO.setLastId(!orderList.isEmpty() ? orderList.get(orderList.size() - 1).getId() : null);
            
            log.info("用户订单列表查询完成，userId:{} 返回数量:{} hasMore:{}", userId, orderInfoList.size(), hasMore);
            return Response.<QueryOrderListResponseDTO>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("查询用户订单列表参数非法: {}", e.getMessage());
            return Response.<QueryOrderListResponseDTO>builder()
                    .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("查询用户订单列表失败，userId:{}", requestDTO.getUserId(), e);
            return Response.<QueryOrderListResponseDTO>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * http://localhost:8080/api/v1/alipay/refund_order
     * <p>
     * {
     * "userId": "xfg02",
     * "orderId": "928263928388"
     * }
     */
    @RequestMapping(value = "refund_order", method = RequestMethod.POST)
    public Response<RefundOrderResponseDTO> refundOrder(@RequestBody RefundOrderRequestDTO requestDTO,
                                                      HttpServletRequest servletRequest) {
        try {
            String userId = gatewayUserResolver.resolveUserId(servletRequest, requestDTO.getUserId());
            String orderId = requestDTO.getOrderId();
            log.info("申请订单退款，userId:{} orderId:{}", userId, orderId);
            
            // 执行订单退款
            boolean success = orderService.refundMarketOrder(userId, orderId);
            
            RefundOrderResponseDTO responseDTO = new RefundOrderResponseDTO();
            responseDTO.setSuccess(success);
            responseDTO.setOrderId(orderId);
            responseDTO.setMessage(success ? "refund success" : "refund failed: order not found, closed, or not owned by user");
            
            log.info("订单退款处理完成，userId:{} orderId:{} success:{}", userId, orderId, success);
            return Response.<RefundOrderResponseDTO>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("订单退款参数非法: {}", e.getMessage());
            RefundOrderResponseDTO responseDTO = new RefundOrderResponseDTO();
            responseDTO.setSuccess(false);
            responseDTO.setOrderId(requestDTO.getOrderId());
            responseDTO.setMessage(e.getMessage());
            return Response.<RefundOrderResponseDTO>builder()
                    .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("订单退款处理失败，userId:{} orderId:{}", requestDTO.getUserId(), requestDTO.getOrderId(), e);
            
            RefundOrderResponseDTO responseDTO = new RefundOrderResponseDTO();
            responseDTO.setSuccess(false);
            responseDTO.setOrderId(requestDTO.getOrderId());
            responseDTO.setMessage("退款失败，请稍后重试");
            
            return Response.<RefundOrderResponseDTO>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .data(responseDTO)
                    .build();
        }
    }

    /**
     * 支付回跳同步结算（用户侧）：支付宝同步回跳后由前端调用，
     * 主动向支付宝查单（alipay.trade.query），TRADE_SUCCESS 即触发与异步 notify 相同的结算逻辑。
     * 用途：本地/演示环境公网 notify_url 打不到本机时，靠该接口完成「支付→发放额度」闭环。
     * 安全：经网关身份头解析用户并校验订单归属；结算幂等（重复调用/与异步 notify 双触发均安全）。
     */
    @RequestMapping(value = "sync_settle", method = RequestMethod.POST)
    public Response<String> syncSettle(@RequestParam String outTradeNo, HttpServletRequest servletRequest) {
        try {
            String userId = gatewayUserResolver.resolveUserId(servletRequest, null);
            OrderEntity order = orderService.queryOrderByOrderId(outTradeNo);
            if (order == null || !userId.equals(order.getUserId())) {
                log.warn("sync settle rejected: order not found or not owned, userId={} outTradeNo={}", userId, outTradeNo);
                return Response.<String>builder()
                        .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("order not found or not owned")
                        .build();
            }

            String status = order.getOrderStatusVO() != null ? order.getOrderStatusVO().getCode() : "";
            boolean alreadySettled = OrderStatusVO.PAY_SUCCESS.getCode().equals(status)
                    || OrderStatusVO.DEAL_DONE.getCode().equals(status)
                    || OrderStatusVO.MARKET.getCode().equals(status);
            if (alreadySettled) {
                return Response.<String>builder()
                        .code(Constants.ResponseCode.SUCCESS.getCode())
                        .info(Constants.ResponseCode.SUCCESS.getInfo())
                        .data("SETTLED")
                        .build();
            }

            AlipayTradeQueryModel bizModel = new AlipayTradeQueryModel();
            bizModel.setOutTradeNo(outTradeNo);
            AlipayTradeQueryRequest queryRequest = new AlipayTradeQueryRequest();
            queryRequest.setBizModel(bizModel);
            String body;
            try {
                body = alipayClient.execute(queryRequest).getBody();
            } catch (Exception queryError) {
                // 支付宝沙箱偶发 504/网关超时时，订单真相仍是本地 PAY_WAIT。
                // 对用户侧轮询返回稳定 UNPAID，避免每三秒触发一次全局错误提示。
                log.warn("sync settle alipay query temporarily unavailable outTradeNo={} reason={}",
                        outTradeNo, queryError.getMessage());
                return Response.<String>builder()
                        .code(Constants.ResponseCode.SUCCESS.getCode())
                        .info(Constants.ResponseCode.SUCCESS.getInfo())
                        .data("UNPAID:QUERY_TEMPORARILY_UNAVAILABLE")
                        .build();
            }
            log.info("sync settle trade query outTradeNo={} response={}", outTradeNo, body);

            JSONObject queryResponse = JSON.parseObject(body).getJSONObject("alipay_trade_query_response");
            if (queryResponse == null || !"10000".equals(queryResponse.getString("code"))) {
                String msg = queryResponse != null ? queryResponse.getString("sub_msg") : "trade query failed";
                return Response.<String>builder()
                        .code(Constants.ResponseCode.SUCCESS.getCode())
                        .info(Constants.ResponseCode.SUCCESS.getInfo())
                        .data("UNPAID:" + msg)
                        .build();
            }

            String tradeStatus = queryResponse.getString("trade_status");
            if (!"TRADE_SUCCESS".equals(tradeStatus)) {
                return Response.<String>builder()
                        .code(Constants.ResponseCode.SUCCESS.getCode())
                        .info(Constants.ResponseCode.SUCCESS.getInfo())
                        .data("UNPAID:" + tradeStatus)
                        .build();
            }

            String gmtPayment = queryResponse.getString("send_pay_date");
            SimpleDateFormat payDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            payDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            orderService.changeOrderPaySuccess(outTradeNo,
                    gmtPayment != null ? payDateFormat.parse(gmtPayment) : new Date());
            log.info("sync settle success userId={} outTradeNo={}", userId, outTradeNo);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data("SETTLED")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<String>builder()
                    .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("sync settle failed outTradeNo={}", outTradeNo, e);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private BigDecimal resolveDisplayAmount(OrderEntity order) {
        if (order == null) {
            return null;
        }
        // The QR request, callback verification and refunds all use the persisted pay_amount.
        // Never reconstruct the amount from a market preview because preview discounts can be stale.
        return order.getPayAmount() != null ? order.getPayAmount() : order.getTotalAmount();
    }

    private boolean isDemoCompletionAvailable() {
        return demoCompleteEnabled && environment != null && environment.acceptsProfiles(Profiles.of("dev"));
    }

    /**
     * 主动查询支付宝交易状态，并在支付成功时更新本地订单。
     * @param outTradeNo 商户订单号
     * @return 查询和处理结果
     */
    @RequestMapping(value = "active_pay_notify", method = RequestMethod.POST)
    public Response<String> activePayNotify(@RequestParam String outTradeNo, HttpServletRequest servletRequest) {
        if (!internalCallbackAuthSupport.isAuthorized(servletRequest)) {
            log.warn("active pay notify rejected: missing or invalid internal token, outTradeNo:{}", outTradeNo);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .data("unauthorized")
                    .build();
        }
        try {
            log.info("主动查询支付宝订单状态，outTradeNo:{}", outTradeNo);
            
            // 构建支付宝交易查询请求
            AlipayTradeQueryModel bizModel = new AlipayTradeQueryModel();
            bizModel.setOutTradeNo(outTradeNo);
            
            AlipayTradeQueryRequest queryRequest = new AlipayTradeQueryRequest();
            queryRequest.setBizModel(bizModel);
            
            // 调用支付宝交易查询 API
            String body = alipayClient.execute(queryRequest).getBody();
            log.info("支付宝交易查询响应: {}", body);
            
            // 解析查询结果
            JSONObject responseJson = JSON.parseObject(body);
            JSONObject queryResponse = responseJson.getJSONObject("alipay_trade_query_response");
            
            if (queryResponse != null && "10000".equals(queryResponse.getString("code"))) {
                String tradeStatus = queryResponse.getString("trade_status");
                String tradeNo = queryResponse.getString("trade_no");
                String totalAmount = queryResponse.getString("total_amount");
                String gmtPayment = queryResponse.getString("send_pay_date");
                
                log.info("查询成功，交易状态:{} 支付宝交易号:{} 订单金额:{} 支付时间:{}",
                        tradeStatus, tradeNo, totalAmount, gmtPayment);
                
                // 支付成功时更新本地订单状态
                if ("TRADE_SUCCESS".equals(tradeStatus)) {
                    log.info("支付宝订单支付成功，开始更新本地订单，outTradeNo:{}", outTradeNo);
                    
                    // 将本地订单更新为支付成功
                    SimpleDateFormat payDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    payDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                    orderService.changeOrderPaySuccess(outTradeNo,
                            gmtPayment != null ? payDateFormat.parse(gmtPayment) : new Date());
                    
                    log.info("本地订单支付状态更新成功，outTradeNo:{}", outTradeNo);
                    
                    return Response.<String>builder()
                            .code(Constants.ResponseCode.SUCCESS.getCode())
                            .info(Constants.ResponseCode.SUCCESS.getInfo())
                            .data("支付成功，订单状态已更新")
                            .build();
                } else {
                    log.info("支付宝订单尚未支付，tradeStatus:{} outTradeNo:{}", tradeStatus, outTradeNo);
                    return Response.<String>builder()
                            .code(Constants.ResponseCode.SUCCESS.getCode())
                            .info(Constants.ResponseCode.SUCCESS.getInfo())
                            .data("当前交易状态: " + tradeStatus)
                            .build();
                }
            } else {
                String errorMsg = queryResponse != null ? queryResponse.getString("msg") : "查询失败";
                log.error("支付宝交易查询失败，errorMsg:{} outTradeNo:{}", errorMsg, outTradeNo);
                return Response.<String>builder()
                        .code(Constants.ResponseCode.UN_ERROR.getCode())
                        .info(Constants.ResponseCode.UN_ERROR.getInfo())
                        .data("查询失败: " + errorMsg)
                        .build();
            }
            
        } catch (Exception e) {
            log.error("主动查询支付宝订单状态异常，outTradeNo:{}", outTradeNo, e);
            return Response.<String>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .data("查询异常: " + e.getMessage())
                    .build();
        }
    }

}
