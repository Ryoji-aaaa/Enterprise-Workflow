export function LogoutForm() {
  return (
    <form action="/api/auth/logout" method="post">
      <button className="button" type="submit">ログアウト</button>
    </form>
  );
}
