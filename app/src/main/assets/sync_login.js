(function (options) {
    'use strict';
    // Called only after native code verifies the exact configured origin.
    const visible = e => e && !e.disabled && e.getClientRects().length > 0;
    const passwords = Array.from(document.querySelectorAll('input[type=password]')).filter(visible);
    if (passwords.length !== 1) return { state: 'no_login' };
    const password = passwords[0];
    const form = password.closest('form') || document;
    const users = Array.from(form.querySelectorAll('input')).filter(e => visible(e) &&
        ['text', 'email', 'tel', ''].indexOf(e.type) >= 0 &&
        !/captcha|verify|yzm|code|验证码/i.test([e.id, e.name, e.placeholder].join(' ')));
    const username = users.find(e => /user|account|yhm|xh|学号|账号|用户名/i.test([e.id, e.name, e.placeholder].join(' '))) ||
        (users.length === 1 ? users[0] : null);
    if (!username) return { state: 'unsupported' };
    if (options.submit && (Array.from(form.querySelectorAll('input')).some(e => visible(e) &&
        /captcha|verify|yzm|验证码/i.test([e.id, e.name, e.placeholder].join(' '))) ||
        document.querySelector('iframe[src*="captcha"],iframe[src*="recaptcha"],.geetest_panel,.g-recaptcha'))) {
        return { state: 'verification' };
    }
    function fill(element, value) {
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        setter.call(element, value);
        element.dispatchEvent(new Event('input', { bubbles: true }));
        element.dispatchEvent(new Event('change', { bubbles: true }));
    }
    fill(username, options.username); fill(password, options.password);
    if (!options.submit) return { state: 'filled' };
    const buttons = Array.from(form.querySelectorAll('button,input[type=submit],input[type=button],a')).filter(visible);
    const button = buttons.find(e => /登录|登\s*录|sign\s*in|log\s*in/i.test(e.innerText || e.value || '')) ||
        buttons.find(e => e.type === 'submit');
    if (!button) return { state: 'unsupported' };
    // The school's own click handler performs RSA/encryption or other normal login logic.
    button.click();
    return { state: 'submitted' };
})
