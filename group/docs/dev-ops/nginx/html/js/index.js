const state = {
    baseUrl: "http://127.0.0.1:8091",
    goodsId: "9890001",
    source: "s01",
    channel: "c01",
    userId: "",
    activityId: 100123,
    goods: null,
    outTradeNo: "",
    currentPayPrice: 0,
    previewMode: false
};

const dom = {};
let toastTimer = null;

document.addEventListener("DOMContentLoaded", () => {
    cacheDom();
    bindGallery();
    bindEvents();

    state.userId = getCookie("username") || "";
    if (!state.userId) {
        window.location.href = "login.html";
        return;
    }

    dom.currentUser.textContent = state.userId;
    renderPendingState();
    renderDock();
    loadMarketConfig();
});

function cacheDom() {
    dom.currentUser = document.getElementById("currentUser");
    dom.logoutButton = document.getElementById("logoutButton");
    dom.refreshButton = document.getElementById("refreshButton");
    dom.teamBoard = document.getElementById("teamBoard");
    dom.productTitle = document.getElementById("productTitle");
    dom.groupPrice = document.getElementById("groupPrice");
    dom.originalPrice = document.getElementById("originalPrice");
    dom.deductionPrice = document.getElementById("deductionPrice");
    dom.activityIdBadge = document.getElementById("activityIdBadge");
    dom.allTeamCount = document.getElementById("allTeamCount");
    dom.allTeamCompleteCount = document.getElementById("allTeamCompleteCount");
    dom.allTeamUserCount = document.getElementById("allTeamUserCount");
    dom.visibleTeamCount = document.getElementById("visibleTeamCount");
    dom.userTradeHint = document.getElementById("userTradeHint");
    dom.pendingTradeNo = document.getElementById("pendingTradeNo");
    dom.primaryAction = document.getElementById("primaryAction");
    dom.secondaryAction = document.getElementById("secondaryAction");
    dom.dockPrice = document.getElementById("dockPrice");
    dom.dockHint = document.getElementById("dockHint");
    dom.modal = document.getElementById("paymentModal");
    dom.closePayment = document.getElementById("closePayment");
    dom.cancelPayment = document.getElementById("cancelPayment");
    dom.completePayment = document.getElementById("completePayment");
    dom.paymentAmount = document.getElementById("paymentAmount");
    dom.outTradeNo = document.getElementById("outTradeNo");
    dom.toast = document.getElementById("toast");
}

function bindEvents() {
    dom.logoutButton.addEventListener("click", handleLogout);
    dom.refreshButton.addEventListener("click", () => loadMarketConfig(true));
    dom.secondaryAction.addEventListener("click", () => loadMarketConfig(true));
    dom.primaryAction.addEventListener("click", handlePrimaryAction);
    dom.teamBoard.addEventListener("click", handleTeamBoardClick);

    dom.closePayment.addEventListener("click", hidePaymentModal);
    dom.cancelPayment.addEventListener("click", hidePaymentModal);
    dom.completePayment.addEventListener("click", completePaymentFlow);
    dom.modal.addEventListener("click", (event) => {
        if (event.target.dataset.close === "true") {
            hidePaymentModal();
        }
    });
}

function bindGallery() {
    const wrapper = document.querySelector(".swiper-wrapper");
    const pagination = document.querySelector(".swiper-pagination");
    const slides = Array.from(document.querySelectorAll(".swiper-slide"));

    if (!wrapper || !pagination || slides.length === 0) {
        return;
    }

    let currentIndex = 0;
    slides.forEach((_, index) => {
        const dot = document.createElement("span");
        dot.className = `swiper-dot${index === 0 ? " active" : ""}`;
        pagination.appendChild(dot);
    });

    const dots = Array.from(pagination.children);

    const renderSlide = () => {
        wrapper.style.transform = `translateX(-${currentIndex * 100}%)`;
        dots.forEach((dot, index) => {
            dot.classList.toggle("active", index === currentIndex);
        });
    };

    window.setInterval(() => {
        currentIndex = (currentIndex + 1) % slides.length;
        renderSlide();
    }, 3200);
}

async function loadMarketConfig(fromManualRefresh = false) {
    if (fromManualRefresh) {
        showToast("正在刷新活动数据...");
    }

    dom.teamBoard.innerHTML = '<div class="loading-state">正在加载活动数据...</div>';

    try {
        const response = await fetch(`${state.baseUrl}/api/v1/gbm/index/query_group_buy_market_config`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                userId: state.userId,
                source: state.source,
                channel: state.channel,
                goodsId: state.goodsId
            })
        });

        const result = await response.json();
        if (result.code !== "0000") {
            throw new Error(result.info || "活动查询失败");
        }

        const data = result.data || {};
        const goods = data.goods || {};
        const teamList = Array.isArray(data.teamList) ? data.teamList : [];
        const teamStatistic = data.teamStatistic || {};

        state.activityId = data.activityId || state.activityId;
        state.goods = goods;
        state.currentPayPrice = Number(goods.payPrice || 0);
        state.outTradeNo = resolvePendingTradeNo(teamList);
        state.previewMode = false;

        renderGoods(goods);
        renderStats(teamStatistic, teamList);
        renderPendingState();
        renderTeamBoard(teamList);
        renderDock();

        if (fromManualRefresh) {
            showToast("活动数据已刷新", "success");
        }
    } catch (error) {
        if ((error.message || "").includes("Failed to fetch")) {
            applyPreviewState();
            showToast("8091 后端未连接，当前展示演示数据", "error");
            return;
        }

        dom.teamBoard.innerHTML = `
            <div class="error-state">
                <h4>活动数据加载失败</h4>
                <p>${safeText(error.message || "请检查后端服务与数据库状态")}</p>
            </div>
        `;
        renderPendingState();
        renderDock();
        showToast(error.message || "活动数据加载失败", "error");
    }
}

function renderGoods(goods) {
    dom.productTitle.textContent = "手写 MyBatis：渐进式源码实践";
    dom.groupPrice.textContent = formatPrice(goods.payPrice);
    dom.originalPrice.textContent = `¥${formatPrice(goods.originalPrice)}`;
    dom.deductionPrice.textContent = `¥${formatPrice(goods.deductionPrice)}`;
    dom.activityIdBadge.textContent = `活动 ID ${state.activityId}`;
}

function renderStats(teamStatistic, teamList) {
    dom.allTeamCount.textContent = teamStatistic.allTeamCount ?? 0;
    dom.allTeamCompleteCount.textContent = teamStatistic.allTeamCompleteCount ?? 0;
    dom.allTeamUserCount.textContent = teamStatistic.allTeamUserCount ?? 0;
    dom.visibleTeamCount.textContent = teamList.length;
}

function renderPendingState() {
    if (state.previewMode) {
        dom.userTradeHint.textContent = "当前处于演示预览模式。待后端恢复后，这里会显示真实的锁单与待支付状态。";
        dom.pendingTradeNo.textContent = "演示态";
        return;
    }

    if (state.outTradeNo) {
        dom.userTradeHint.textContent = "当前账号已有一笔待支付锁单，可以直接继续支付完成结算。";
        dom.pendingTradeNo.textContent = state.outTradeNo;
        return;
    }

    dom.userTradeHint.textContent = "当前没有待支付锁单。你可以先发起新团，或直接加入其他进行中的队伍。";
    dom.pendingTradeNo.textContent = "无";
}

function renderDock() {
    dom.dockPrice.textContent = `¥${formatPrice(state.currentPayPrice)}`;

    if (state.previewMode) {
        dom.dockHint.textContent = "当前是静态演示数据。启动 8091 后端后可继续真实联调。";
        dom.primaryAction.textContent = "发起开团";
        return;
    }

    if (state.outTradeNo) {
        dom.dockHint.textContent = "待支付订单已生成，可继续支付完成模拟结算";
        dom.primaryAction.textContent = "继续支付";
        return;
    }

    dom.dockHint.textContent = "发起一个新团，成为当前活动的下一位队长";
    dom.primaryAction.textContent = "发起开团";
}

function renderTeamBoard(teamList) {
    const previewBanner = state.previewMode ? `
        <article class="team-card">
            <div class="team-owner">
                <span class="owner-chip neutral">演示数据</span>
                <strong>当前展示本地预览布局</strong>
                <small>后端恢复后，页面会自动显示真实拼团队伍和统计结果。</small>
            </div>
        </article>
    ` : "";

    if (!teamList.length) {
        dom.teamBoard.innerHTML = `
            ${previewBanner}
            <div class="empty-state">
                <h4>当前还没有可加入的队伍</h4>
                <p>你可以直接发起新团，先占住当前活动名额。</p>
            </div>
        `;
        return;
    }

    const hasPendingOrder = Boolean(state.outTradeNo);
    const payPriceText = `¥${formatPrice(state.currentPayPrice)}`;

    dom.teamBoard.innerHTML = previewBanner + teamList.map((team) => {
        const targetCount = Number(team.targetCount || 0);
        const lockCount = Number(team.lockCount || 0);
        const completeCount = Number(team.completeCount || 0);
        const remaining = Math.max(targetCount - lockCount, 0);
        const progress = targetCount > 0 ? Math.min((lockCount / targetCount) * 100, 100) : 0;
        const isMine = team.userId === state.userId;
        const buttonDisabled = hasPendingOrder && !isMine;
        const buttonLabel = hasPendingOrder ? (isMine ? "继续支付" : "已有待支付订单") : "加入此团";

        return `
            <article class="team-card">
                <div class="team-card-head">
                    <div class="team-owner">
                        <span class="owner-chip${isMine ? "" : " neutral"}">${isMine ? "我的队伍" : "队长发起"}</span>
                        <strong>${safeText(team.userId || "匿名用户")}</strong>
                        <small>队伍编号 ${safeText(team.teamId || "--")}</small>
                    </div>
                    <div class="progress-note">剩余 ${remaining} 人成团</div>
                </div>

                <div class="team-progress">
                    <div class="progress-top">
                        <strong>${lockCount}/${targetCount} 已锁定</strong>
                        <span class="progress-note">已成团 ${completeCount}</span>
                    </div>
                    <div class="progress-track">
                        <div class="progress-bar" style="width:${progress}%;"></div>
                    </div>
                </div>

                <div class="team-meta">
                    <span>倒计时 ${safeText(team.validTimeCountdown || "00:00:00")}</span>
                    <span>活动 ${safeText(team.activityId || state.activityId)}</span>
                </div>

                <div class="team-action">
                    <div class="team-price">
                        <span class="progress-note">拼团支付价</span>
                        <strong>${payPriceText}</strong>
                    </div>
                    <button
                        class="team-btn ${buttonDisabled ? "" : "primary"}"
                        type="button"
                        data-action="join-team"
                        data-team-id="${safeAttribute(team.teamId || "")}"
                        ${buttonDisabled ? "disabled" : ""}
                    >
                        ${buttonLabel}
                    </button>
                </div>
            </article>
        `;
    }).join("");
}

function handlePrimaryAction() {
    if (state.previewMode) {
        showToast("后端未启动，当前只能预览界面布局", "error");
        return;
    }

    if (state.outTradeNo) {
        showPaymentModal(state.currentPayPrice);
        return;
    }

    createLockOrder(null);
}

function handleTeamBoardClick(event) {
    const target = event.target.closest("[data-action='join-team']");
    if (!target || target.disabled) {
        return;
    }

    if (state.previewMode) {
        showToast("后端未启动，当前只能预览界面布局", "error");
        return;
    }

    if (state.outTradeNo) {
        showPaymentModal(state.currentPayPrice);
        return;
    }

    createLockOrder(target.dataset.teamId || null);
}

async function createLockOrder(teamId) {
    const outTradeNo = generateRandomNumber(12);

    try {
        dom.primaryAction.disabled = true;
        showToast(teamId ? "正在加入队伍..." : "正在创建新团...");

        const response = await fetch(`${state.baseUrl}/api/v1/gbm/trade/lock_market_pay_order`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                userId: state.userId,
                teamId: teamId,
                activityId: state.activityId,
                goodsId: state.goodsId,
                source: state.source,
                channel: state.channel,
                outTradeNo: outTradeNo,
                notifyConfigVO: {
                    notifyType: "HTTP",
                    notifyUrl: `${state.baseUrl}/api/v1/test/group_buy_notify`
                }
            })
        });

        const result = await response.json();
        if (result.code !== "0000") {
            throw new Error(result.info || "锁单失败");
        }

        state.outTradeNo = outTradeNo;
        renderPendingState();
        renderDock();
        showPaymentModal(state.currentPayPrice);
        showToast("锁单成功，请继续支付", "success");
        await loadMarketConfig();
    } catch (error) {
        showToast(error.message || "锁单失败", "error");
    } finally {
        dom.primaryAction.disabled = false;
    }
}

function showPaymentModal(price) {
    if (!state.outTradeNo) {
        showToast("当前没有待支付锁单", "error");
        return;
    }

    dom.paymentAmount.textContent = `¥${formatPrice(price || state.currentPayPrice)}`;
    dom.outTradeNo.textContent = state.outTradeNo;
    dom.modal.classList.add("is-open");
    dom.modal.setAttribute("aria-hidden", "false");
}

function hidePaymentModal() {
    dom.modal.classList.remove("is-open");
    dom.modal.setAttribute("aria-hidden", "true");
}

async function completePaymentFlow() {
    if (!state.outTradeNo) {
        showToast("当前没有待支付锁单", "error");
        return;
    }

    dom.completePayment.disabled = true;

    try {
        const response = await fetch(`${state.baseUrl}/api/v1/gbm/trade/settlement_market_pay_order`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                source: state.source,
                channel: state.channel,
                userId: state.userId,
                outTradeNo: state.outTradeNo,
                outTradeTime: new Date()
            })
        });

        const result = await response.json();
        if (result.code !== "0000") {
            throw new Error(result.info || "结算失败");
        }

        hidePaymentModal();
        showToast("支付与结算已完成", "success");
        state.outTradeNo = "";
        renderPendingState();
        renderDock();
        await loadMarketConfig();
    } catch (error) {
        showToast(error.message || "结算失败", "error");
    } finally {
        dom.completePayment.disabled = false;
    }
}

function handleLogout() {
    document.cookie = "username=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
    window.location.href = "login.html";
}

function resolvePendingTradeNo(teamList) {
    const myTeam = teamList.find((team) => team.userId === state.userId && team.outTradeNo);
    return myTeam ? myTeam.outTradeNo : "";
}

function applyPreviewState() {
    const previewData = getPreviewData();
    state.activityId = previewData.activityId;
    state.goods = previewData.goods;
    state.currentPayPrice = Number(previewData.goods.payPrice || 0);
    state.outTradeNo = "";
    state.previewMode = true;

    renderGoods(previewData.goods);
    renderStats(previewData.teamStatistic, previewData.teamList);
    renderPendingState();
    renderTeamBoard(previewData.teamList);
    renderDock();
}

function getPreviewData() {
    return {
        activityId: 100123,
        goods: {
            goodsId: "9890001",
            originalPrice: 99,
            deductionPrice: 20,
            payPrice: 79
        },
        teamStatistic: {
            allTeamCount: 12,
            allTeamCompleteCount: 7,
            allTeamUserCount: 86
        },
        teamList: [
            {
                userId: "studio-anna",
                teamId: "T20260416001",
                activityId: 100123,
                targetCount: 5,
                completeCount: 2,
                lockCount: 3,
                validTimeCountdown: "01:28:16"
            },
            {
                userId: "market-lee",
                teamId: "T20260416002",
                activityId: 100123,
                targetCount: 5,
                completeCount: 1,
                lockCount: 4,
                validTimeCountdown: "00:42:51"
            }
        ]
    };
}

function getCookie(name) {
    const cookieArr = document.cookie.split(";");
    for (const cookie of cookieArr) {
        const cookiePair = cookie.split("=");
        if (name === cookiePair[0].trim()) {
            return decodeURIComponent(cookiePair[1]);
        }
    }
    return null;
}

function generateRandomNumber(length) {
    let result = "";
    for (let index = 0; index < length; index += 1) {
        result += Math.floor(Math.random() * 10);
    }
    return result;
}

function formatPrice(value) {
    const numericValue = Number(value || 0);
    return new Intl.NumberFormat("zh-CN", {
        minimumFractionDigits: 0,
        maximumFractionDigits: 2
    }).format(numericValue);
}

function showToast(message, type = "info") {
    dom.toast.textContent = message;
    dom.toast.className = `toast show ${type}`;

    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => {
        dom.toast.className = "toast";
    }, 2400);
}

function safeText(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function safeAttribute(value) {
    return safeText(value);
}
