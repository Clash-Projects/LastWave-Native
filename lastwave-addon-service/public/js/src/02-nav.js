/* 02-nav — active topnav/tabbar indicator. */
(function nav() {
  var path = location.pathname;
  document.querySelectorAll('[data-nav]').forEach(function (a) {
    var k = a.getAttribute('data-nav');
    var on =
      (k === 'home' && path === '/') ||
      (k === 'account' && (path === '/dashboard' || path === '/login' || path === '/signup' || path.indexOf('/verify') === 0)) ||
      (k === 'admin' && path.indexOf('/admin') === 0);
    if (on) a.setAttribute('aria-current', 'page');
  });
})();
