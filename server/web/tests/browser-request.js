// APIRequestContext is a test HTTP client, so it does not generate browser Origin
// or Referer headers. Supply the trusted origin explicitly for cookie fixtures;
// requests made by the real page continue to exercise native browser behavior.
export function browserRequest(subject) {
  const origin = new URL(process.env.TIMEKEEPER_WEB_URL).origin;
  return Object.fromEntries(['get','post','put','delete','fetch'].map(method => [method,
    (url,options={}) => subject.request[method](url,{...options,headers:{Origin:origin,...options.headers}})]));
}
