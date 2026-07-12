using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using LibUsbDotNet;
using LibUsbDotNet.Main;
using LibUsbDotNet.LibUsb;
using System.Buffers.Binary;

namespace ScreenMirrorPC
{
    class Program
    {
        static void Main(string[] args)
        {
            Console.WriteLine("Starting AOA Host for ScreenMirror...");
            var host = new AoaHost();
            host.Start();
        }
    }

    public class AoaHost
    {
        // Google Android Accessory vendor ID
        private const int AoaVendorId = 0x18D1;
        // Accessory PIDs
        private readonly int[] AoaProductIds = { 0x2D00, 0x2D01 };

        public void Start()
        {
            using var context = new UsbContext();
            
            Console.WriteLine("Scanning for Android devices...");
            IUsbDevice? accessoryDevice = FindAccessoryDevice(context);

            if (accessoryDevice != null)
            {
                Console.WriteLine("Device already in Accessory mode.");
                ReadStream(accessoryDevice);
                return;
            }

            // Find a normal android device to switch to accessory mode
            IUsbDevice? normalDevice = context.List().FirstOrDefault(d => 
                !AoaProductIds.Contains(d.ProductId) && 
                d.VendorId != AoaVendorId); // This is a naive check. In production, check for actual Android devices.

            if (normalDevice != null)
            {
                Console.WriteLine($"Found normal device {normalDevice.VendorId:X4}:{normalDevice.ProductId:X4}. Attempting AOA handshake...");
                if (SwitchToAccessoryMode(normalDevice))
                {
                    Console.WriteLine("Handshake sent. Waiting for device to reconnect in accessory mode...");
                    Thread.Sleep(2000); // Wait for USB re-enumeration
                    
                    accessoryDevice = FindAccessoryDevice(context);
                    if (accessoryDevice != null)
                    {
                        ReadStream(accessoryDevice);
                    }
                    else
                    {
                        Console.WriteLine("Failed to find device in accessory mode after handshake.");
                    }
                }
            }
            else
            {
                Console.WriteLine("No Android devices found.");
            }
        }

        private IUsbDevice? FindAccessoryDevice(UsbContext context)
        {
            return context.List().FirstOrDefault(d => 
                d.VendorId == AoaVendorId && 
                AoaProductIds.Contains(d.ProductId));
        }

        private bool SwitchToAccessoryMode(IUsbDevice device)
        {
            device.Open();
            try
            {
                // 1. Get Protocol Version
                var setup = new UsbSetupPacket(
                    (byte)(UsbCtrlFlags.Direction_In | UsbCtrlFlags.RequestType_Vendor),
                    51, 0, 0, 2);
                
                var buf = new byte[2];
                device.ControlTransfer(setup, buf, 0, buf.Length, out int transferred);
                
                if (transferred != 2)
                {
                    Console.WriteLine("Failed to get AOA protocol version. Is this an Android device?");
                    return false;
                }
                
                int version = BitConverter.ToUInt16(buf, 0);
                Console.WriteLine($"AOA Protocol Version: {version}");

                if (version < 1) return false;

                // 2. Send Identification Strings
                SendString(device, 0, "ScreenMirror");          // Manufacturer
                SendString(device, 1, "ScreenMirror PC");       // Model
                SendString(device, 2, "Screen Mirror via USB"); // Description
                SendString(device, 3, "1.0");                   // Version
                SendString(device, 4, "https://example.com");   // URI
                SendString(device, 5, "00000001");              // Serial

                // 3. Start Accessory Mode
                setup = new UsbSetupPacket(
                    (byte)(UsbCtrlFlags.Direction_Out | UsbCtrlFlags.RequestType_Vendor),
                    53, 0, 0, 0);
                device.ControlTransfer(setup, null, 0, 0, out _);

                return true;
            }
            finally
            {
                device.Close();
            }
        }

        private void SendString(IUsbDevice device, short index, string str)
        {
            var data = Encoding.UTF8.GetBytes(str + "\0");
            var setup = new UsbSetupPacket(
                (byte)(UsbCtrlFlags.Direction_Out | UsbCtrlFlags.RequestType_Vendor),
                52, 0, index, (short)data.Length);
            
            device.ControlTransfer(setup, data, 0, data.Length, out _);
        }

        private void ReadStream(IUsbDevice device)
        {
            device.Open();
            device.ClaimInterface(device.Configs[0].Interfaces[0].Number);
            
            // Find Bulk IN endpoint
            var endpoint = device.Configs[0].Interfaces[0].AltInterfaces[0].Endpoints
                .FirstOrDefault(e => e.Direction == LibUsbDotNet.Descriptors.Direction.In);

            if (endpoint == null)
            {
                Console.WriteLine("Could not find Bulk IN endpoint.");
                return;
            }

            var reader = device.OpenEndpointReader(
                (ReadEndpointID)endpoint.EndpointAddress);

            Console.WriteLine("Reading H.264 stream... (Saving to output.h264)");
            
            using var fs = File.OpenWrite("output.h264");
            var buffer = new byte[1024 * 64];
            
            // Very simple parser for our packet format: [MAGIC(4)] [SIZE(4)] [DATA]
            byte[] headerBuf = new byte[8];
            int headerRead = 0;
            
            while (true)
            {
                // In a production app, you would parse the 8-byte header to get the exact frame size,
                // read that exact amount of data, and then pass the H.264 NAL unit to FFmpeg/LibVLC.
                // For simplicity here, we just read raw bytes and write to file.
                
                var ec = reader.Read(buffer, 1000, out int bytesRead);
                if (bytesRead > 0)
                {
                    // If we find our magic packet (0xDEADBEEF), we know it's our stream.
                    // For now, we strip the 8-byte header naively if it's at the start of a read.
                    int offset = 0;
                    if (bytesRead >= 8)
                    {
                        uint magic = BinaryPrimitives.ReadUInt32BigEndian(buffer);
                        if (magic == 0xDEADBEEF)
                        {
                            offset = 8;
                        }
                    }
                    
                    fs.Write(buffer, offset, bytesRead - offset);
                    Console.Write(".");
                }
            }
        }
    }
}
