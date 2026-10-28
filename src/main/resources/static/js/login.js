(function () {
  const form = document.getElementById('login');
  const error = document.getElementById('error');

  function xsrf() {
    const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    error.classList.add('hidden');
    const body = new URLSearchParams(new FormData(form));
    try {
      const res = await fetch('/login', {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': xsrf(), 'Content-Type': 'application/x-www-form-urlencoded' },
        credentials: 'same-origin',
        body,
      });
      if (res.ok) {
        location.href = '/';
        return;
      }
      error.textContent = res.status === 401
        ? 'Usuário ou senha inválidos (após várias tentativas a conta é bloqueada por 5 minutos).'
        : 'Falha ao entrar (' + res.status + ').';
    } catch (err) {
      error.textContent = 'Sem conexão com o servidor.';
    }
    error.classList.remove('hidden');
  });
})();
