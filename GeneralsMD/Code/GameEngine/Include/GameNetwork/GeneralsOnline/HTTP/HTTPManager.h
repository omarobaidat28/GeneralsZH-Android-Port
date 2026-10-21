#pragma once

#include "HTTPRequest.h"
#include <curl/multi.h>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
// GeneralsX @bugfix Android port 16/09/2026 See HTTPManager.cpp's
// DeterminePlatformProxySettings() for why this is not just #if defined(_WIN32).
#if defined(_WIN32) && defined(GENERALS_ONLINE_USE_WINHTTP_PROXY_DETECTION)
#include <winhttp.h>
#pragma comment(lib, "winhttp.lib")
#endif
#include "GameNetwork/GeneralsOnline/NGMP_include.h"

enum class EHTTPVerb
{
	HTTP_VERB_GET,
	HTTP_VERB_POST,
	HTTP_VERB_PUT,
	HTTP_VERB_DELETE
};

enum class EIPProtocolVersion
{
	DONT_CARE = 0,
	FORCE_IPV4 = 4,
	FORCE_IPV6 = 6
};

// GeneralsX @feature Android port 21/10/2026 Network & ping optimization (tester-reported
// latency regressions). CURLOPT_OPENSOCKETFUNCTION callback shared by every libcurl handle
// in the online services (HTTP requests and the WebSocket both call curl_easy_init()): it
// (a) enables TCP_NODELAY so the game's small request/response and lobby keepalive frames
// are never queued behind Nagle's algorithm waiting for a full segment, and (b) raises
// SO_RCVBUF/SO_SNDBUF above the OS default so mobile-network jitter-then-burst patterns
// (short stall, then several windowfuls) do not drop frames on the receive side. The
// previous behavior relied on libcurl's defaults (TCP_NODELAY on, ~208 KB buffers), so
// this is strictly a hardening + documented-guarantee change, not a wire-format one.
curl_socket_t GxNetworkSocketConfigure(void* clientp, curlsocktype purpose, struct curl_sockaddr* addr);

class HTTPManager
{
public:
	HTTPManager() noexcept;
	~HTTPManager();

	void Initialize();

	void Tick();

	void AddHandleToMulti(CURL* pNewHandle);
	void RemoveHandleFromMulti(CURL* pHandleToRemove);

	void SendGETRequest(const char* szURI, EIPProtocolVersion protover, std::map<std::string, std::string>& inHeaders, std::function<void(bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)> completionCallback, std::function<void(size_t bytesReceived)> progressCallback = nullptr, int timeoutMS = -1);
	// GeneralsX @bugfix Android port 03/10/2026 bDisableServiceAuth from upstream: the token refresh
	// authenticates with the refresh token, which the session token would otherwise overwrite.
	void SendPOSTRequest(const char* szURI, EIPProtocolVersion protover, std::map<std::string, std::string>& inHeaders, const char* szPostData, std::function<void(bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)> completionCallback, std::function<void(size_t bytesReceived)> progressCallback = nullptr, int timeoutMS = -1, bool bDisableServiceAuth = false);
	void SendS3PUTRequest(const char* szURI, EIPProtocolVersion protover, std::map<std::string, std::string>& inHeaders, std::vector<uint8_t> vecBuffer, std::function<void(bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)> completionCallback, std::function<void(size_t bytesReceived)> progressCallback = nullptr, int timeoutMS = -1);
	void SendPUTRequest(const char* szURI, EIPProtocolVersion protover, std::map<std::string, std::string>& inHeaders, const char* szData, std::function<void(bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)> completionCallback, std::function<void(size_t bytesReceived)> progressCallback = nullptr, int timeoutMS = -1);
	void SendDELETERequest(const char* szURI, EIPProtocolVersion protover, std::map<std::string, std::string>& inHeaders, const char* szData, std::function<void(bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)> completionCallback, std::function<void(size_t bytesReceived)> progressCallback = nullptr, int timeoutMS = -1);

	void Shutdown();

	bool IsProxyEnabled() const { return m_bProxyEnabled; }

	bool DeterminePlatformProxySettings();
	std::string& GetProxyAddress() { return m_strProxyAddr; }
	uint16_t GetProxyPort() const { return m_proxyPort; }

private:
	HTTPRequest* PlatformCreateRequest(EHTTPVerb htpVerb, EIPProtocolVersion protover, const char* szURI, std::map<std::string, std::string>& inHeaders, std::function<void(bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)> completionCallback,
		std::function<void(size_t bytesReceived)> progressCallback = nullptr, int timeoutMS = -1) noexcept;

private:
	CURLM* m_pCurl = nullptr;

	bool m_bProxyEnabled = false;
	std::string m_strProxyAddr;
	uint16_t m_proxyPort;

	std::atomic<bool> m_bShuttingDown = false;

	std::vector<HTTPRequest*> m_vecRequestsPendingStart = std::vector<HTTPRequest*>();
	std::vector<HTTPRequest*> m_vecRequestsInFlight = std::vector<HTTPRequest*>();
};


