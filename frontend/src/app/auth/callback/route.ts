import { NextResponse, type NextRequest } from "next/server";
import { SIGN_IN_COOKIE, TOKEN_COOKIE, appUrl, config, cookieOptions, discover, type SignIn } from "@/lib/oidc";

// Where the OIDC provider returns after sign-in: swaps the code for an access
// token and keeps it in an httpOnly cookie for the /api proxy
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const error = params.get("error");
  if (error) {
    return new Response(`Sign-in failed: ${params.get("error_description") ?? error}`, { status: 400 });
  }

  let signIn: SignIn;
  try {
    signIn = JSON.parse(request.cookies.get(SIGN_IN_COOKIE)?.value ?? "");
  } catch {
    return new Response("Sign-in expired; please try again", { status: 400 });
  }
  const code = params.get("code");
  if (!code || params.get("state") !== signIn.state) {
    return new Response("Sign-in failed: invalid state", { status: 400 });
  }

  const body = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    redirect_uri: `${appUrl(request)}/auth/callback`,
    client_id: config.clientId,
    code_verifier: signIn.verifier,
  });
  if (config.clientSecret) body.set("client_secret", config.clientSecret);

  let token: { access_token?: string; expires_in?: number };
  try {
    const response = await fetch((await discover()).token_endpoint, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body,
      cache: "no-store",
    });
    token = await response.json();
    if (!response.ok || !token.access_token) throw new Error(`token request failed: ${response.status}`);
  } catch {
    return new Response("Sign-in failed: the OIDC provider did not issue a token", { status: 502 });
  }

  const response = NextResponse.redirect(new URL(signIn.returnTo, appUrl(request)));
  response.cookies.set(TOKEN_COOKIE, token.access_token, cookieOptions(request, token.expires_in ?? 300));
  response.cookies.delete(SIGN_IN_COOKIE);
  return response;
}
