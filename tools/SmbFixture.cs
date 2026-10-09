// Local read-only SMB2/3 fixture. Compile with SMBLibrary and SMBLibrary.Win32 1.5.5.
using System;
using System.IO;
using System.Net;
using System.Threading;
using SMBLibrary;
using SMBLibrary.Server;
using SMBLibrary.Win32;
using SMBLibrary.Authentication.GSSAPI;
using SMBLibrary.Authentication.NTLM;

class TestServer : SMBServer {
    public TestServer(SMBShareCollection shares, GSSProvider provider) : base(shares, provider) {}
    public void StartLocal() { Start(IPAddress.Loopback, SMBTransportType.DirectTCPTransport, 1445, false, true, true, null); }
}
class SmbFixture {
    static void Main(string[] args) {
        var folder = Path.GetFullPath(args[0]);
        var share = new FileSystemShare("media", new NTDirectoryFileSystem(folder));
        share.AccessRequested += delegate(object sender, AccessRequestArgs request) {
            request.Allow = request.RequestedAccess == FileAccess.Read;
        };
        var shares = new SMBShareCollection(); shares.Add(share);
        var provider = new GSSProvider(new IndependentNTLMAuthenticationProvider(
            delegate(string user) { return user == "viewer" ? "localtv-test" : null; }));
        var server = new TestServer(shares, provider);
        server.StartLocal();
        Console.WriteLine("SMB fixture at 127.0.0.1:1445/media");
        Thread.Sleep(Timeout.Infinite);
    }
}
