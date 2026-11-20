// Windows single-file starter for the Alloy installer (build_exe.py builds it with the csc of .NET Framework 4).
// The exe carries payload.zip: a jpackage app image (the installer jar plus a trimmed Java runtime). On the first start
// it unpacks that into %LOCALAPPDATA%\Alloy\installer-VERSION-STAMP and runs it from there; later starts reuse it.
// No arguments (a double click): the installer window. With arguments: the console launcher, its output is passed
// through to this exe's console or redirected output, and the exit code is returned.
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class Boot {
    [DllImport("kernel32.dll")] static extern bool AttachConsole(int pid);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int which);
    [DllImport("kernel32.dll")] static extern int GetFileType(IntPtr h);

    /** a usable handle the parent gave (a file or pipe of "exe --help > out.txt", or a console) */
    static bool Valid(IntPtr h) { return h != IntPtr.Zero && h != new IntPtr(-1) && GetFileType(h) != 0; }

    static Stream Std(IntPtr saved, bool err) {
        if (Valid(saved))
            try { return new FileStream(new Microsoft.Win32.SafeHandles.SafeFileHandle(saved, false), FileAccess.Write, 1); }
            catch (Exception) { }
        return err ? Console.OpenStandardError() : Console.OpenStandardOutput();
    }

    static string Res(string name) {
        using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
        using (StreamReader r = new StreamReader(s, Encoding.UTF8)) return r.ReadToEnd().Trim();
    }

    [STAThread]
    static int Main(string[] args) {
        bool cli = args.Length > 0;
        // a GUI exe gets the parent's redirected handles, but AttachConsole would swap them for the console: save them first
        IntPtr out0 = GetStdHandle(-11), err0 = GetStdHandle(-12);
        if (cli && (!Valid(out0) || !Valid(err0))) AttachConsole(-1);
        try {
            string dir = cli ? Unpack() : UnpackWithWindow();
            string exe = Path.Combine(dir, cli ? "alloy-cli.exe" : "Alloy Installer.exe");
            ProcessStartInfo psi = new ProcessStartInfo(exe, Join(args));
            psi.UseShellExecute = false;
            psi.WorkingDirectory = Environment.CurrentDirectory;
            if (cli) {
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.CreateNoWindow = true;
            }
            Process p = Process.Start(psi);
            if (cli) {
                Thread o = Pump(p.StandardOutput.BaseStream, Std(out0, false));
                Thread e = Pump(p.StandardError.BaseStream, Std(err0, true));
                p.WaitForExit();
                o.Join();
                e.Join();
            } else p.WaitForExit();
            return p.ExitCode;
        } catch (Exception ex) {
            string msg = "Alloy Installer could not start: " + ex.Message;
            if (cli) {
                byte[] b = Encoding.UTF8.GetBytes(msg + Environment.NewLine);
                try { Stream s = Std(err0, true); s.Write(b, 0, b.Length); s.Flush(); } catch (Exception) { }
            } else MessageBox.Show(msg, "Alloy Installer", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    static Thread Pump(Stream from, Stream to) {
        Thread t = new Thread(delegate () {
            byte[] buf = new byte[8192];
            try {
                for (int n; (n = from.Read(buf, 0, buf.Length)) > 0; ) { to.Write(buf, 0, n); to.Flush(); }
            } catch (Exception) { }
        });
        t.IsBackground = true;
        t.Start();
        return t;
    }

    /** the first start unpacks for a few seconds: a small window says so, so nobody clicks again */
    static string UnpackWithWindow() {
        string done = Ready();
        if (done != null) return done;
        Form f = new Form();
        f.Text = "Alloy Installer";
        f.FormBorderStyle = FormBorderStyle.FixedDialog;
        f.StartPosition = FormStartPosition.CenterScreen;
        f.ControlBox = false;
        f.ClientSize = new System.Drawing.Size(360, 70);
        Label l = new Label();
        l.Text = "Unpacking Alloy Installer " + Res("version.txt") + ", one moment...";
        l.Dock = DockStyle.Fill;
        l.TextAlign = System.Drawing.ContentAlignment.MiddleCenter;
        f.Controls.Add(l);
        string result = null;
        Exception err = null;
        f.Shown += delegate {
            Thread t = new Thread(delegate () {
                try { result = Unpack(); } catch (Exception ex) { err = ex; }
                f.BeginInvoke((MethodInvoker) delegate { f.Close(); });
            });
            t.IsBackground = true;
            t.Start();
        };
        Application.Run(f);
        if (err != null) throw err;
        return result;
    }

    static string Target() {
        string root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrEmpty(root)) root = Path.GetTempPath();
        return Path.Combine(Path.Combine(root, "Alloy"), "installer-" + Res("version.txt") + "-" + Res("stamp.txt"));
    }

    static string Ready() {
        string dir = Target();
        return File.Exists(Path.Combine(dir, ".complete")) ? dir : null;
    }

    static string Unpack() {
        string done = Ready();
        if (done != null) return done;
        string dir = Target();
        string tmp = dir + ".tmp" + Process.GetCurrentProcess().Id;
        if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
        Directory.CreateDirectory(tmp);
        using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
        using (ZipArchive z = new ZipArchive(s, ZipArchiveMode.Read)) z.ExtractToDirectory(tmp);
        File.WriteAllText(Path.Combine(tmp, ".complete"), Res("stamp.txt"));
        try {
            if (Directory.Exists(dir)) Directory.Delete(dir, true);   // an unfinished older unpack
            Directory.Move(tmp, dir);
        } catch (IOException) {
            if (Ready() == null) throw;   // another start finished first: use its copy
            try { Directory.Delete(tmp, true); } catch (Exception) { }
        }
        return dir;
    }

    /** Windows command line quoting (CommandLineToArgvW rules) */
    static string Join(string[] args) {
        StringBuilder b = new StringBuilder();
        foreach (string a in args) {
            if (b.Length > 0) b.Append(' ');
            if (a.Length > 0 && a.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0) { b.Append(a); continue; }
            b.Append('"');
            int slashes = 0;
            foreach (char c in a) {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') b.Append('\\', slashes * 2 + 1);
                else b.Append('\\', slashes);
                slashes = 0;
                b.Append(c);
            }
            b.Append('\\', slashes * 2);
            b.Append('"');
        }
        return b.ToString();
    }
}
