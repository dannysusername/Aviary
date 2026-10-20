// Import with AI page: Copy button for the MCP address. Falls back to
// selecting the address when the browser won't allow clipboard access.
document.addEventListener('DOMContentLoaded', function () {
    const button = document.getElementById('copy-mcp-url');
    const field = document.getElementById('mcp-url');
    if (!button || !field) return;

    function showFailure() {
        button.textContent = 'Copy failed — select the address';
        field.focus();
        field.select();
    }

    button.addEventListener('click', function () {
        if (!navigator.clipboard || !navigator.clipboard.writeText) {
            showFailure();
            return;
        }
        navigator.clipboard.writeText(field.value).then(function () {
            button.textContent = 'Copied';
            setTimeout(function () { button.textContent = 'Copy'; }, 2000);
        }, showFailure);
    });
});
