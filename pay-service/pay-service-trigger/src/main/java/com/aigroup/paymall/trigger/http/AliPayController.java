package com.aigroup.paymall.trigger.http;

import com.aigroup.paymall.api.dto.CreatePayQrResponseDTO;
import com.aigroup.paymall.api.dto.CreatePayRequestDTO;
import com.aigroup.paymall.api.dto.NotifyRequestDTO;
import com.aigroup.paymall.api.dto.QueryOrderListRequestDTO;
import com.aigroup.paymall.api.dto.QueryOrderListResponseDTO;
import com.aigroup.paymall.api.dto.PayOrderResponseDTO;
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
import com.aigroup.paymall.types.enums.ResponseCode;
import com.aigroup.paymall.types.exception.AppException;
import com.aigroup.paymall.types.common.JsonUtils;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
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
@RequestMapping({"/api/v1/alipay/", "/api/pay/"})
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
    @RequestMapping(value = {"create_pay_order", "orders"}, method = RequestMethod.POST)
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
                    .requestId(createPayRequestDTO.getRequestId())
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
        } catch (AppException e) {
            log.warn("创建支付订单被拒绝: code={} reason={}", e.getCode(), e.getInfo());
            return Response.<String>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
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
                    .requestId(createPayRequestDTO.getRequestId())
                    .userId(userId)
                    .productId(productId)
                    .productCode(productCode)
                    .teamId(teamId)
                    .marketTypeVO(MarketTypeVO.valueOf(marketType))
                    .activityId(createPayRequestDTO.getActivityId())
                    .build());

            // 只有成功插入订单的 owner 才能触发支付宝预下单；幂等回放只读取已持久化结果。
            String qrCode = payOrderEntity.isIdempotentReplay()
                    ? null
                    : orderService.prepareTradeQrCode(payOrderEntity.getOrderId());
            OrderEntity persistedOrder = orderService.queryOrderByOrderId(payOrderEntity.getOrderId());
            if (payOrderEntity.isIdempotentReplay()) {
                qrCode = resolvePersistedQrCode(persistedOrder);
                if (qrCode == null) {
                    throw new AppException(ResponseCode.ORDER_CREATION_REVIEW.getCode(),
                            "qr provider result is not durably available, orderId:" + payOrderEntity.getOrderId());
                }
            }

            return Response.<CreatePayQrResponseDTO>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(CreatePayQrResponseDTO.builder()
                             .orderId(payOrderEntity.getOrderId())
                             .qrCode(qrCode)
                             .payUrl(persistedOrder == null ? null : persistedOrder.getPayUrl())
                             .amount(resolveDisplayAmount(persistedOrder))
                             .demoCompletionEnabled(isDemoCompletionAvailable())
                             .build())
                    .build();
        } catch (AppException e) {
            log.warn("create pay qrcode rejected code={} reason={}", e.getCode(), e.getInfo());
            return Response.<CreatePayQrResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
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

    @RequestMapping(value = {"group_buy_notify", "group/notify"}, method = RequestMethod.POST)
    public String groupBuyNotify(@RequestBody NotifyRequestDTO requestDTO, HttpServletRequest servletRequest) {
        if (!internalCallbackAuthSupport.isAuthorized(servletRequest)) {
            log.warn("group buy notify rejected: missing or invalid internal token");
            return "error";
        }
        log.info("group buy notify settlement start {}", JsonUtils.toJson(requestDTO));
        try {
            orderService.changeOrderMarketSettlement(requestDTO.getOutTradeNoList());
            return "success";
        } catch (Exception e) {
            log.error("group buy notify settlement failed {}", JsonUtils.toJson(requestDTO), e);
            return "error";
        }
    }

    /**
     * POST /api/v1/alipay/alipay_notify_url
     */
    @RequestMapping(value = {"alipay_notify_url", "alipay/notify"}, method = RequestMethod.POST)
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
    @RequestMapping(value = {"query_user_order_list", "orders/page"}, method = RequestMethod.POST)
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
     * "userId": "demo-user-02",
     * "orderId": "928263928388"
     * }
     */
    @RequestMapping(value = {"refund_order", "orders/refund"}, method = RequestMethod.POST)
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

    /** Canonical read endpoint used by the BFF order detail page. */
    @GetMapping("orders/{orderId}")
    public Response<PayOrderResponseDTO> getOrder(
            @PathVariable String orderId,
            HttpServletRequest servletRequest) {
        try {
            String userId = gatewayUserResolver.resolveUserId(servletRequest, null);
            OrderEntity order = orderService.queryOrderByOrderId(orderId);
            if (order == null || !userId.equals(order.getUserId())) {
                return Response.<PayOrderResponseDTO>builder()
                        .code(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("order not found or not owned")
                        .build();
            }
            PayOrderResponseDTO data = new PayOrderResponseDTO();
            data.setOrderId(order.getOrderId());
            data.setUserId(order.getUserId());
            data.setProductCode(order.getProductCode());
            data.setTotalAmount(order.getTotalAmount());
            data.setPayAmount(order.getPayAmount());
            data.setStatus(order.getOrderStatusVO() == null ? null : order.getOrderStatusVO().getCode());
            data.setPayUrl(order.getPayUrl());
            data.setOrderTime(order.getOrderTime());
            data.setPayTime(order.getPayTime());
            data.setGroupActivityId(order.getGroupActivityId());
            data.setGroupTeamId(order.getGroupTeamId());
            data.setDemoCompletionEnabled(isDemoCompletionAvailable());
            return Response.<PayOrderResponseDTO>builder()
                    .code(Constants.ResponseCode.SUCCESS.getCode())
                    .info(Constants.ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询支付订单失败 orderId={}", orderId, e);
            return Response.<PayOrderResponseDTO>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(Constants.ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /** Cancel an unpaid order through the canonical payment API. */
    @PostMapping("orders/{orderId}/cancel")
    public Response<RefundOrderResponseDTO> cancelOrder(
            @PathVariable String orderId,
            HttpServletRequest servletRequest) {
        String userId = gatewayUserResolver.resolveUserId(servletRequest, null);
        RefundOrderResponseDTO responseDTO = new RefundOrderResponseDTO();
        responseDTO.setOrderId(orderId);
        try {
            boolean success = orderService.refundMarketOrder(userId, orderId);
            responseDTO.setSuccess(success);
            responseDTO.setMessage(success ? "order cancelled" : "order cannot be cancelled");
            return Response.<RefundOrderResponseDTO>builder()
                    .code(success ? Constants.ResponseCode.SUCCESS.getCode()
                            : Constants.ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(responseDTO.getMessage())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("cancel order failed userId={} orderId={}", userId, orderId, e);
            responseDTO.setSuccess(false);
            responseDTO.setMessage("cancel failed");
            return Response.<RefundOrderResponseDTO>builder()
                    .code(Constants.ResponseCode.UN_ERROR.getCode())
                    .info(responseDTO.getMessage())
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
            AlipayTradeQueryResponse queryResponse;
            try {
                queryResponse = alipayClient.execute(queryRequest);
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
            log.info("sync settle trade query outTradeNo={} code={} tradeStatus={}",
                    outTradeNo, queryResponse.getCode(), queryResponse.getTradeStatus());

            if (!queryResponse.isSuccess() || !"10000".equals(queryResponse.getCode())) {
                String msg = queryResponse.getSubMsg() == null ? "trade query failed" : queryResponse.getSubMsg();
                return Response.<String>builder()
                        .code(Constants.ResponseCode.SUCCESS.getCode())
                        .info(Constants.ResponseCode.SUCCESS.getInfo())
                        .data("UNPAID:" + msg)
                        .build();
            }

            String tradeStatus = queryResponse.getTradeStatus();
            if (!"TRADE_SUCCESS".equals(tradeStatus)) {
                return Response.<String>builder()
                        .code(Constants.ResponseCode.SUCCESS.getCode())
                        .info(Constants.ResponseCode.SUCCESS.getInfo())
                        .data("UNPAID:" + tradeStatus)
                        .build();
            }

            Date gmtPayment = queryResponse.getSendPayDate();
            orderService.changeOrderPaySuccess(outTradeNo,
                    gmtPayment != null ? gmtPayment : new Date());
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

    private String resolvePersistedQrCode(OrderEntity order) {
        if (order == null || order.getPayUrl() == null) {
            return null;
        }
        String payUrl = order.getPayUrl().trim();
        return payUrl.startsWith("https://") || payUrl.startsWith("http://") ? payUrl : null;
    }

    private boolean isDemoCompletionAvailable() {
        return demoCompleteEnabled && environment != null && environment.acceptsProfiles(Profiles.of("dev"));
    }

}
