function getRandomName() {
  return "__callback" + Math.round(Math.random() * 100000000);
}

async function callRpc(verb, args) {
  const prom = new Promise((resolve, reject) => {
    const some_random_name = getRandomName();
    window[some_random_name] = [resolve, reject];
    window.Android.callRpc(verb, JSON.stringify(args), some_random_name);
  });
  console.log("about to send out");
  let res = JSON.parse(await prom);
  return res;
}

window["NATIVE_GATE"] = {
  async start_daemon(params) {
    await callRpc("start_daemon", [params]);
    while (true) {
      try {
        await this.is_connected();
        break;
      } catch (e) {
        await new Promise((r) => setTimeout(r, 200));
        continue;
      }
    }
  },
  async stop_daemon() {
    await this.daemon_rpc("kill", []);
    // await callRpc("stop_daemon", []);
  },
  async is_connected() {
    return await this.daemon_rpc("is_connected", []);
  },
  async is_running() {
    try {
      await this.daemon_rpc("is_connected", []);
      return true;
    } catch (e) {
      return false;
    }
  },
  async sync_user_info(auth_kind) {
    let sync_info = await callRpc("sync", [auth_kind, false]);
    if (sync_info.user.subscription)
      return {
        level: sync_info.user.subscription.level.toLowerCase(),
        expires: sync_info.user.subscription
          ? new Date(sync_info.user.subscription.expires_unix * 1000.0)
          : null,
      };
    else return { level: "free", expires: null };
  },

  async daemon_rpc(method, args) {
    const req = { jsonrpc: "2.0", method: method, params: args, id: 1 };
    const resp = await callRpc("daemon_rpc", [JSON.stringify(req)]);
    if (resp.error) {
      throw resp.error.message;
    }
    return resp.result;
  },

async binder_rpc(method, args) {
  const req = { jsonrpc: "2.0", method: method, params: args, id: 1 };
  const resp = await callRpc("binder_rpc", [JSON.stringify(req)]);
  if (resp.error) {
    throw resp.error.message;
  }
  return resp.result;
},
  async sync_exits(auth_kind) {
    let sync_info = await callRpc("sync", [auth_kind, false]);
    return sync_info.exits;
  },

  async purge_caches(auth_kind) {
    await callRpc("sync", [auth_kind, true]);
  },

  supports_app_whitelist: true,
  supports_listen_all: true,

  async sync_app_list() {
    const result = await callRpc("get_app_list", []);
    result.sort((a, b) => a.friendly_name.localeCompare(b.friendly_name));
    return result;
  },

  get_app_icon_url: async (id) => {
    return await callRpc("get_app_icon", [id]);
  },

  async export_debug_pack() {
    await callRpc("export_logs", []);
  },
  supports_autoupdate: JSON.parse(window.Android.jsHasPlay()),

  async get_native_info() {
        return {
          platform_type: "android",
          platform_details: "Android", version: window.Android.jsVersion(),
        };
  }
};
