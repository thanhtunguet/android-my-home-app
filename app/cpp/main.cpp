#include <iostream>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include "httplib.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <jni.h>

// --- Configuration --- //
const char* get_env(const char* name, const char* default_val) {
    const char* value = std::getenv(name);
    return value ? value : default_val;
}

static std::string DEVICE_MAC = get_env("DEVICE_MAC", "00:00:00:00:00:00");
static std::string SERVER_IP = get_env("SERVER_IP", "127.0.0.1");
static int SHUTDOWN_PORT = 10675;
static int PROBE_PORT = 3389; // RDP port, a good indicator of being online

static std::atomic<bool> g_running{false};
static std::thread g_server_thread;
static httplib::Server* g_server = nullptr;

// --- Core Logic --- //

bool send_magic_packet(const std::string& mac_address) {
    std::vector<unsigned char> mac_bytes;
    for (size_t i = 0; i < mac_address.length(); i += 3) {
        mac_bytes.push_back(static_cast<unsigned char>(std::stoul(mac_address.substr(i, 2), nullptr, 16)));
    }

    if (mac_bytes.size() != 6) {
        std::cerr << "Invalid MAC address format." << std::endl;
        return false;
    }

    std::vector<unsigned char> magic_packet(102);
    std::fill(magic_packet.begin(), magic_packet.begin() + 6, 0xFF);
    for (int i = 1; i <= 16; ++i) {
        std::copy(mac_bytes.begin(), mac_bytes.end(), magic_packet.begin() + (i * 6));
    }

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        perror("socket");
        return false;
    }

    int broadcast = 1;
    if (setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast)) < 0) {
        perror("setsockopt");
        close(sock);
        return false;
    }

    sockaddr_in broadcast_addr;
    broadcast_addr.sin_family = AF_INET;
    broadcast_addr.sin_port = htons(9);
    broadcast_addr.sin_addr.s_addr = inet_addr("255.255.255.255");

    if (sendto(sock, magic_packet.data(), magic_packet.size(), 0, (struct sockaddr*)&broadcast_addr, sizeof(broadcast_addr)) < 0) {
        perror("sendto");
        close(sock);
        return false;
    }

    close(sock);
    return true;
}

bool send_shutdown_command_udp() {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        perror("socket (udp)");
        return false;
    }

    sockaddr_in server_addr;
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(SHUTDOWN_PORT);
    server_addr.sin_addr.s_addr = inet_addr(SERVER_IP.c_str());

    const std::string command = "shutdown-my-pc";
    if (sendto(sock, command.c_str(), command.length(), 0, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        perror("sendto (udp)");
        close(sock);
        return false;
    }

    close(sock);
    std::cout << "UDP shutdown command sent successfully." << std::endl;
    return true;
}

bool send_shutdown_command_tcp() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        perror("socket (tcp)");
        return false;
    }

    sockaddr_in server_addr;
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(SHUTDOWN_PORT);
    server_addr.sin_addr.s_addr = inet_addr(SERVER_IP.c_str());

    if (connect(sock, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        perror("connect (tcp)");
        close(sock);
        return false;
    }

    const std::string command = "shutdown-my-pc";
    if (send(sock, command.c_str(), command.length(), 0) < 0) {
        perror("send (tcp)");
        close(sock);
        return false;
    }

    close(sock);
    std::cout << "TCP shutdown command sent successfully." << std::endl;
    return true;
}

bool send_shutdown_command() {
    bool udp_success = send_shutdown_command_udp();
    bool tcp_success = send_shutdown_command_tcp();
    return udp_success || tcp_success; // Return true if at least one succeeds
}

bool is_pc_online() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        return false;
    }

    // Set a timeout for the connection attempt
    struct timeval timeout;
    timeout.tv_sec = 1; // 1 second
    timeout.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    sockaddr_in server_addr;
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(PROBE_PORT);
    server_addr.sin_addr.s_addr = inet_addr(SERVER_IP.c_str());

    if (connect(sock, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        close(sock);
        return false;
    }

    close(sock);
    return true;
}

// --- Web Server (reusable) --- //

static void run_server(int port) {
    httplib::Server local_svr;

    // Add a logger for requests
    local_svr.set_logger([](const httplib::Request& req, const httplib::Response& res) {
        std::cout << "Request: " << req.method << " " << req.path << " -> Response: " << res.status << std::endl;
    });

    local_svr.Get("/turn-on", [](const httplib::Request&, httplib::Response& res) {
        std::cout << "Action: Attempting to send magic packet to " << DEVICE_MAC << std::endl;
        if (send_magic_packet(DEVICE_MAC)) {
            res.set_content("Magic packet sent.", "text/plain");
            std::cout << "Result: Success." << std::endl;
        } else {
            res.status = 500;
            res.set_content("Failed to send magic packet.", "text/plain");
            std::cerr << "Result: Failure." << std::endl;
        }
    });

    local_svr.Get("/turn-off", [](const httplib::Request&, httplib::Response& res) {
        std::cout << "Action: Attempting to send shutdown command to " << SERVER_IP << ":" << SHUTDOWN_PORT << std::endl;
        if (send_shutdown_command()) {
            res.set_content("Shutdown command sent.", "text/plain");
            std::cout << "Result: Success." << std::endl;
        } else {
            res.status = 500;
            res.set_content("Failed to send shutdown command.", "text/plain");
            std::cerr << "Result: Failure." << std::endl;
        }
    });

    local_svr.Get("/is-online", [](const httplib::Request&, httplib::Response& res) {
        std::cout << "Action: Checking online status for " << SERVER_IP << ":" << PROBE_PORT << std::endl;
        bool online = is_pc_online();
        res.set_content(online ? "true" : "false", "text/plain");
        std::cout << "Result: PC is " << (online ? "online" : "offline") << "." << std::endl;
    });

    g_server = &local_svr;
    std::cout << "--- SmartHomePCControl-CPP (Android) ---" << std::endl;
    std::cout << "Configuration:" << std::endl;
    std::cout << "  - DEVICE_MAC: " << DEVICE_MAC << std::endl;
    std::cout << "  - SERVER_IP:  " << SERVER_IP << std::endl;
    std::cout << "------------------------------" << std::endl;
    std::cout << "Starting server on port " << port << "..." << std::endl;

    g_running.store(true);
    if (!local_svr.listen("0.0.0.0", port)) {
        std::cerr << "Failed to bind to port " << port << ". Is it already in use?" << std::endl;
    }
    g_running.store(false);
    g_server = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_info_thanhtunguet_myhome_NativeServer_start(JNIEnv* env, jclass, jstring jDeviceMac, jstring jServerIp, jint jPort, jint jShutdownPort, jint jProbePort) {
    const char* c_device_mac = env->GetStringUTFChars(jDeviceMac, nullptr);
    const char* c_server_ip = env->GetStringUTFChars(jServerIp, nullptr);
    DEVICE_MAC = c_device_mac ? std::string(c_device_mac) : DEVICE_MAC;
    SERVER_IP = c_server_ip ? std::string(c_server_ip) : SERVER_IP;
    SHUTDOWN_PORT = static_cast<int>(jShutdownPort);
    PROBE_PORT = static_cast<int>(jProbePort);
    if (c_device_mac) env->ReleaseStringUTFChars(jDeviceMac, c_device_mac);
    if (c_server_ip) env->ReleaseStringUTFChars(jServerIp, c_server_ip);

    int port = static_cast<int>(jPort);
    if (g_running.load()) return;
    g_server_thread = std::thread([port]() { run_server(port); });
}

extern "C" JNIEXPORT void JNICALL
Java_info_thanhtunguet_myhome_NativeServer_stop(JNIEnv*, jclass) {
    if (g_server) {
        g_server->stop();
    }
    if (g_server_thread.joinable()) {
        g_server_thread.join();
    }
}

#ifndef __ANDROID__
// --- Standalone desktop entrypoint --- //
int main(int argc, char** argv) {
    int port = 8080;
    if (argc > 1) {
        try {
            port = std::stoi(argv[1]);
        } catch (const std::exception& e) {
            std::cerr << "Invalid port number '" << argv[1] << "'. Using default 8080. Error: " << e.what() << std::endl;
        }
    }
    run_server(port);
    return 0;
}
#endif