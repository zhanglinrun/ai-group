document.addEventListener("DOMContentLoaded", () => {
    const loginForm = document.getElementById("loginForm");
    const usernameInput = document.getElementById("username");
    const passwordInput = document.getElementById("password");
    const errorMessage = document.getElementById("errorMessage");
    const userChips = Array.from(document.querySelectorAll(".user-chip"));

    const rememberedUser = getCookie("username");
    if (rememberedUser) {
        usernameInput.value = rememberedUser;
    }

    userChips.forEach((chip) => {
        chip.addEventListener("click", () => {
            usernameInput.value = chip.dataset.user || "";
            passwordInput.focus();
        });
    });

    loginForm.addEventListener("submit", (event) => {
        event.preventDefault();

        const username = usernameInput.value.trim();
        const password = passwordInput.value.trim();

        if (!username) {
            showError("请输入一个用于本地联调的用户名");
            usernameInput.focus();
            return;
        }

        if (!password) {
            showError("请输入任意密码内容以完成表单提交");
            passwordInput.focus();
            return;
        }

        errorMessage.textContent = "";
        const expirationDate = new Date();
        expirationDate.setDate(expirationDate.getDate() + 1);
        document.cookie = `username=${encodeURIComponent(username)}; expires=${expirationDate.toUTCString()}; path=/`;

        window.location.href = "index.html";
    });

    function showError(message) {
        errorMessage.textContent = message;
    }
});

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
