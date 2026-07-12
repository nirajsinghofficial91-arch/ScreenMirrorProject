using System;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using LibUsbDotNet;
using LibUsbDotNet.Main;

namespace ScreenMirrorPC
{
    class Program
    {
        static void Main(string[] args)
        {
            Console.WriteLine("=== ScreenMirror PC - AOA USB Host ===");
            Console.WriteLine("Plug in your Android phone via USB cable.");
            Console.WriteLine();

            var host = new AoaHost();
            host.Start();
        }
    }

    /// <summary>
    /// Implements the Android Open Accessory (AOA) protocol host side.
    /// Sends AOA handshake to put the phone into accessory mode,
    /// then reads H.264 video stream from the USB bulk endpoint.
    /// </summary>
    public class AoaHost
    {
        // Google AOA vendor ID (after phone switches to accessory mode)
        private const int AOA_VID = 0x18D1;
        // AOA Product IDs (accessory mode)
        private const int AOA_PID_ACCESSORY = 0x2D00;
        private const int AOA_PID_ACCESSORY_ADB = 0x2D01;

        // AOA Protocol USB control transfer request codes
        private const byte AOA_GET_PROTOCOL = 51;
        private const byte AOA_SEND_STRING = 52;
        private const byte AOA_START = 53;

        // Our accessory identification — must match accessory_filter.xml on the Android app
        private const string MANUFACTURER = "ScreenMirror";
        private const string MODEL = "ScreenMirror PC";
        private const string DESCRIPTION = "Screen Mirror via USB";
        private const string VERSION = "1.0";
        private const string URI = "https://example.com";
        private const string SERIAL = "00000001";

        // Packet protocol constants
        private const uint PACKET_MAGIC = 0xDEADBEEF;

        public void Start()
        {
            // Step 1: Check if a device is already in accessory mode
            UsbDevice? device = FindAccessoryDevice();

            if (device != null)
            {
                Console.WriteLine("[OK] Device already in Accessory mode.");
                ReadVideoStream(device);
                return;
            }

            // Step 2: Find any connected USB device and try AOA handshake
            Console.WriteLine("[..] Scanning for Android devices...");
            device = FindAndroidDevice();

            if (device == null)
            {
                Console.WriteLine("[FAIL] No Android device found. Make sure your phone is plugged in via USB.");
                Console.WriteLine("Press any key to exit.");
                Console.ReadKey();
                return;
            }

            Console.WriteLine($"[OK] Found USB device: VID={device.Info.Descriptor.VendorID:X4} PID={device.Info.Descriptor.ProductID:X4}");

            // Step 3: Perform AOA handshake
            if (!PerformAoaHandshake(device))
            {
                Console.WriteLine("[FAIL] AOA handshake failed. Device may not support Android Open Accessory.");
                device.Close();
                return;
            }

            device.Close();
            Console.WriteLine("[..] Waiting for device to re-enumerate in accessory mode...");
            Thread.Sleep(3000); // Wait for USB re-enumeration

            // Step 4: Find the device again (now in accessory mode)
            device = FindAccessoryDevice();
            if (device == null)
            {
                Console.WriteLine("[FAIL] Device did not switch to accessory mode.");
                Console.WriteLine("Make sure the ScreenMirror app is installed on your phone.");
                Console.WriteLine("Press any key to exit.");
                Console.ReadKey();
                return;
            }

            Console.WriteLine("[OK] Device is now in Accessory mode!");
            ReadVideoStream(device);
        }

        /// <summary>
        /// Find a USB device that is already in AOA accessory mode.
        /// </summary>
        private UsbDevice? FindAccessoryDevice()
        {
            var finder1 = new UsbDeviceFinder(AOA_VID, AOA_PID_ACCESSORY);
            var finder2 = new UsbDeviceFinder(AOA_VID, AOA_PID_ACCESSORY_ADB);

            UsbDevice? dev = UsbDevice.OpenUsbDevice(finder1);
            if (dev != null) return dev;

            dev = UsbDevice.OpenUsbDevice(finder2);
            return dev;
        }

        /// <summary>
        /// Find any connected USB device to attempt AOA handshake on.
        /// In production you would filter by known Android vendor IDs.
        /// </summary>
        private UsbDevice? FindAndroidDevice()
        {
            var allDevices = UsbDevice.AllDevices;
            foreach (UsbRegistry registry in allDevices)
            {
                // Skip Google AOA devices (already in accessory mode)
                if (registry.Vid == AOA_VID &&
                    (registry.Pid == AOA_PID_ACCESSORY || registry.Pid == AOA_PID_ACCESSORY_ADB))
                    continue;

                // Try to open this device and check if it supports AOA
                if (registry.Open(out UsbDevice dev))
                {
                    return dev;
                }
            }
            return null;
        }

        /// <summary>
        /// Perform the AOA handshake: get protocol version, send identification strings,
        /// and start accessory mode.
        /// </summary>
        private bool PerformAoaHandshake(UsbDevice device)
        {
            // Ensure we have the whole device (not just an interface)
            IUsbDevice? wholeDevice = device as IUsbDevice;
            wholeDevice?.SetConfiguration(1);

            try
            {
                // 1. Get AOA Protocol Version
                var versionBuf = new byte[2];
                var setup = new UsbSetupPacket(
                    (byte)(UsbCtrlFlags.Direction_In | UsbCtrlFlags.RequestType_Vendor | UsbCtrlFlags.Recipient_Device),
                    AOA_GET_PROTOCOL, 0, 0, 2);

                int transferred;
                bool ok = device.ControlTransfer(ref setup, versionBuf, versionBuf.Length, out transferred);

                if (!ok || transferred != 2)
                {
                    Console.WriteLine("[FAIL] Could not get AOA protocol version.");
                    return false;
                }

                int version = BitConverter.ToUInt16(versionBuf, 0);
                Console.WriteLine($"[OK] AOA Protocol Version: {version}");

                if (version < 1)
                {
                    Console.WriteLine("[FAIL] Device does not support AOA v1+.");
                    return false;
                }

                // 2. Send identification strings
                SendAoaString(device, 0, MANUFACTURER);
                SendAoaString(device, 1, MODEL);
                SendAoaString(device, 2, DESCRIPTION);
                SendAoaString(device, 3, VERSION);
                SendAoaString(device, 4, URI);
                SendAoaString(device, 5, SERIAL);
                Console.WriteLine("[OK] Sent accessory identification strings.");

                // 3. Request device to switch to accessory mode
                setup = new UsbSetupPacket(
                    (byte)(UsbCtrlFlags.Direction_Out | UsbCtrlFlags.RequestType_Vendor | UsbCtrlFlags.Recipient_Device),
                    AOA_START, 0, 0, 0);

                device.ControlTransfer(ref setup, null, 0, out _);
                Console.WriteLine("[OK] Accessory mode start command sent.");

                return true;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[FAIL] AOA handshake error: {ex.Message}");
                return false;
            }
        }

        /// <summary>
        /// Send a string to the device during AOA handshake.
        /// </summary>
        private void SendAoaString(UsbDevice device, short index, string value)
        {
            var data = Encoding.UTF8.GetBytes(value + "\0");
            var setup = new UsbSetupPacket(
                (byte)(UsbCtrlFlags.Direction_Out | UsbCtrlFlags.RequestType_Vendor | UsbCtrlFlags.Recipient_Device),
                AOA_SEND_STRING, 0, index, (short)data.Length);

            device.ControlTransfer(ref setup, data, data.Length, out _);
        }

        /// <summary>
        /// Read the H.264 video stream from the USB accessory device.
        /// Parses the packet protocol: [MAGIC(4)] [SIZE(4)] [DATA(N)]
        /// Saves raw H.264 to output.h264 file.
        /// </summary>
        private void ReadVideoStream(UsbDevice device)
        {
            IUsbDevice? wholeDevice = device as IUsbDevice;
            wholeDevice?.SetConfiguration(1);
            wholeDevice?.ClaimInterface(0);

            // Open Bulk IN endpoint (endpoint 0x81 is standard for AOA)
            var reader = device.OpenEndpointReader(ReadEndpointID.Ep01);

            string outputFile = Path.Combine(Environment.CurrentDirectory, "output.h264");
            Console.WriteLine($"[OK] Connected! Reading H.264 stream...");
            Console.WriteLine($"[OK] Saving to: {outputFile}");
            Console.WriteLine("[..] Press Ctrl+C to stop.");
            Console.WriteLine();

            long totalBytes = 0;
            int frameCount = 0;
            var startTime = DateTime.Now;

            using var fs = File.Create(outputFile);
            var readBuffer = new byte[16384]; // 16KB read buffer

            // State machine for packet parsing
            var headerBuf = new byte[8];
            int headerPos = 0;
            int payloadRemaining = 0;
            bool inPayload = false;

            while (true)
            {
                var ec = reader.Read(readBuffer, 2000, out int bytesRead);

                if (bytesRead <= 0)
                {
                    if (ec == ErrorCode.Win32Error || ec == ErrorCode.IoTimedOut)
                    {
                        // Timeout is normal when no frames are being sent
                        continue;
                    }
                    Console.WriteLine($"\n[WARN] USB read returned {ec}. Cable disconnected?");
                    break;
                }

                // Process the received bytes through our packet parser
                int pos = 0;
                while (pos < bytesRead)
                {
                    if (!inPayload)
                    {
                        // Reading header
                        int needed = 8 - headerPos;
                        int available = bytesRead - pos;
                        int toCopy = Math.Min(needed, available);

                        Buffer.BlockCopy(readBuffer, pos, headerBuf, headerPos, toCopy);
                        headerPos += toCopy;
                        pos += toCopy;

                        if (headerPos == 8)
                        {
                            // Parse header
                            uint magic = BinaryPrimitives.ReadUInt32BigEndian(headerBuf.AsSpan(0, 4));
                            if (magic == PACKET_MAGIC)
                            {
                                payloadRemaining = (int)BinaryPrimitives.ReadUInt32BigEndian(headerBuf.AsSpan(4, 4));
                                inPayload = true;
                                frameCount++;
                            }
                            else
                            {
                                // Not a valid packet header — could be raw H.264 data
                                // Write the header bytes as data and continue
                                fs.Write(headerBuf, 0, 8);
                                totalBytes += 8;
                            }
                            headerPos = 0;
                        }
                    }
                    else
                    {
                        // Reading payload
                        int available = bytesRead - pos;
                        int toCopy = Math.Min(payloadRemaining, available);

                        fs.Write(readBuffer, pos, toCopy);
                        totalBytes += toCopy;
                        pos += toCopy;
                        payloadRemaining -= toCopy;

                        if (payloadRemaining == 0)
                        {
                            inPayload = false;

                            // Print progress
                            var elapsed = (DateTime.Now - startTime).TotalSeconds;
                            double fps = elapsed > 0 ? frameCount / elapsed : 0;
                            Console.Write($"\rFrames: {frameCount} | Data: {totalBytes / 1024}KB | FPS: {fps:F1}  ");
                        }
                    }
                }
            }

            Console.WriteLine("\n[OK] Stream ended.");

            wholeDevice?.ReleaseInterface(0);
            device.Close();
        }
    }
}
