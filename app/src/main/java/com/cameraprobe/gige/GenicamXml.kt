package com.cameraprobe.gige

/**
 * Стандартное GenICam XML описание камеры Hikrobot.
 * Сообщает приложению MVS доступные функции (Width, Height, PixelFormat, AcquisitionStart/Stop).
 */
object GenicamXml {

    // ВАЖНО: <?xml должно начинаться СТРОГО с 0-го байта, без переносов строк перед ним!
    val XML_DATA: String = """<?xml version="1.0" encoding="utf-8"?>
<RegisterDescription
	ModelName="MV-CS004-10GM"
	VendorName="Hikrobot"
	StandardNameSpace="GEV"
	SchemaMajorVersion="1"
	SchemaMinorVersion="0"
	SchemaSubMinorVersion="0"
	MajorVersion="1"
	MinorVersion="2"
	SubMinorVersion="0"
	ToolTip="Hikrobot GigE Vision Camera"
	ProductGuid="EE4B7E09-DA29-4956-A1EC-212263331CFC"
	VersionGuid="79ace398-62e3-44a6-8845-f81aaf104382"
	xmlns="http://www.genicam.org/GenApi/Version_1_0"
	xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://www.genicam.org/GenApi/Version_1_0 GenApiSchema_Version_1_0.xsd">

	<Category Name="Root" NameSpace="Standard">
		<pFeature>DeviceControl</pFeature>
		<pFeature>ImageFormatControl</pFeature>
		<pFeature>AcquisitionControl</pFeature>
		<pFeature>AnalogControl</pFeature>
		<pFeature>FocusControl</pFeature>
		<pFeature>TransportLayerControl</pFeature>
	</Category>

	<!-- DeviceControl -->
	<Category Name="DeviceControl" NameSpace="Standard">
		<pFeature>DeviceScanType</pFeature>
		<pFeature>DeviceVendorName</pFeature>
		<pFeature>DeviceModelName</pFeature>
		<pFeature>DeviceManufacturerInfo</pFeature>
		<pFeature>DeviceVersion</pFeature>
		<pFeature>DeviceID</pFeature>
		<pFeature>DeviceSerialNumber</pFeature>
	</Category>

	<Enumeration Name="DeviceScanType" NameSpace="Standard">
		<EnumEntry Name="Areascan" NameSpace="Standard">
			<Value>0</Value>
		</EnumEntry>
		<pValue>DeviceScanTypeReg</pValue>
	</Enumeration>
	<IntReg Name="DeviceScanTypeReg">
		<Address>0xA01C</Address>
		<Length>4</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<StringReg Name="DeviceVendorName" NameSpace="Standard">
		<Address>0x48</Address>
		<Length>32</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
	</StringReg>

	<StringReg Name="DeviceModelName" NameSpace="Standard">
		<Address>0x68</Address>
		<Length>32</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
	</StringReg>

	<StringReg Name="DeviceManufacturerInfo" NameSpace="Standard">
		<Address>0xa8</Address>
		<Length>48</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
	</StringReg>

	<StringReg Name="DeviceVersion" NameSpace="Standard">
		<Address>0x88</Address>
		<Length>32</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
	</StringReg>

	<StringReg Name="DeviceID" NameSpace="Standard">
		<Address>0xd8</Address>
		<Length>16</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
	</StringReg>

	<StringReg Name="DeviceSerialNumber" NameSpace="Standard">
		<Address>0xd8</Address>
		<Length>16</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
	</StringReg>

	<!-- ImageFormatControl -->
	<Category Name="ImageFormatControl" NameSpace="Standard">
		<pFeature>Width</pFeature>
		<pFeature>Height</pFeature>
		<pFeature>PixelFormat</pFeature>
	</Category>

	<Integer Name="Width" NameSpace="Standard">
		<pValue>WidthReg</pValue>
		<Min>320</Min>
		<Max>4096</Max>
		<Inc>16</Inc>
	</Integer>
	<IntReg Name="WidthReg">
		<Address>0xA000</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="Height" NameSpace="Standard">
		<pValue>HeightReg</pValue>
		<Min>240</Min>
		<Max>3072</Max>
		<Inc>2</Inc>
	</Integer>
	<IntReg Name="HeightReg">
		<Address>0xA004</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Enumeration Name="PixelFormat" NameSpace="Standard">
		<EnumEntry Name="Mono8" NameSpace="Standard">
			<Value>17301505</Value>
		</EnumEntry>
		<EnumEntry Name="RGB8Packed" NameSpace="Standard">
			<Value>35127316</Value>
		</EnumEntry>
		<pValue>PixelFormatReg</pValue>
	</Enumeration>
	<IntReg Name="PixelFormatReg">
		<Address>0xA008</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<!-- AcquisitionControl -->
	<Category Name="AcquisitionControl" NameSpace="Standard">
		<pFeature>AcquisitionMode</pFeature>
		<pFeature>AcquisitionStart</pFeature>
		<pFeature>AcquisitionStop</pFeature>
		<pFeature>TriggerMode</pFeature>
		<pFeature>ExposureAuto</pFeature>
		<pFeature>ExposureTime</pFeature>
	</Category>

	<Enumeration Name="AcquisitionMode" NameSpace="Standard">
		<EnumEntry Name="Continuous" NameSpace="Standard">
			<Value>2</Value>
		</EnumEntry>
		<EnumEntry Name="SingleFrame" NameSpace="Standard">
			<Value>0</Value>
		</EnumEntry>
		<EnumEntry Name="MultiFrame" NameSpace="Standard">
			<Value>1</Value>
		</EnumEntry>
		<pValue>AcquisitionModeReg</pValue>
	</Enumeration>
	<IntReg Name="AcquisitionModeReg">
		<Address>0xA00C</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Enumeration Name="TriggerMode" NameSpace="Standard">
		<EnumEntry Name="Off" NameSpace="Standard">
			<Value>0</Value>
		</EnumEntry>
		<EnumEntry Name="On" NameSpace="Standard">
			<Value>1</Value>
		</EnumEntry>
		<pValue>TriggerModeReg</pValue>
	</Enumeration>
	<IntReg Name="TriggerModeReg">
		<Address>0xA018</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Command Name="AcquisitionStart" NameSpace="Standard">
		<pValue>AcquisitionStartReg</pValue>
		<CommandValue>1</CommandValue>
	</Command>
	<IntReg Name="AcquisitionStartReg">
		<Address>0xA010</Address>
		<Length>4</Length>
		<AccessMode>WO</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Command Name="AcquisitionStop" NameSpace="Custom">
		<pValue>AcquisitionStopReg</pValue>
		<CommandValue>0</CommandValue>
	</Command>
	<IntReg Name="AcquisitionStopReg">
		<Address>0xA014</Address>
		<Length>4</Length>
		<AccessMode>WO</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Enumeration Name="ExposureAuto" NameSpace="Standard">
		<EnumEntry Name="Off" NameSpace="Standard">
			<Value>0</Value>
		</EnumEntry>
		<EnumEntry Name="Continuous" NameSpace="Standard">
			<Value>1</Value>
		</EnumEntry>
		<pValue>ExposureAutoReg</pValue>
	</Enumeration>
	<IntReg Name="ExposureAutoReg">
		<Address>0xA040</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="ExposureTime" NameSpace="Standard">
		<ToolTip>Exposure time in microseconds (100 to 50000 us)</ToolTip>
		<pValue>ExposureTimeReg</pValue>
		<Min>100</Min>
		<Max>50000</Max>
		<Inc>100</Inc>
	</Integer>
	<IntReg Name="ExposureTimeReg">
		<Address>0xA044</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<!-- AnalogControl -->
	<Category Name="AnalogControl" NameSpace="Standard">
		<pFeature>GainAuto</pFeature>
		<pFeature>Gain</pFeature>
	</Category>

	<Enumeration Name="GainAuto" NameSpace="Standard">
		<EnumEntry Name="Off" NameSpace="Standard">
			<Value>0</Value>
		</EnumEntry>
		<EnumEntry Name="Continuous" NameSpace="Standard">
			<Value>1</Value>
		</EnumEntry>
		<pValue>GainAutoReg</pValue>
	</Enumeration>
	<IntReg Name="GainAutoReg">
		<Address>0xA048</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="Gain" NameSpace="Standard">
		<ToolTip>Sensor gain ISO (100 to 3200)</ToolTip>
		<pValue>GainReg</pValue>
		<Min>100</Min>
		<Max>3200</Max>
		<Inc>10</Inc>
	</Integer>
	<IntReg Name="GainReg">
		<Address>0xA04C</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<!-- FocusControl -->
	<Category Name="FocusControl" NameSpace="Standard">
		<pFeature>FocusAuto</pFeature>
		<pFeature>FocusDistance</pFeature>
	</Category>

	<Enumeration Name="FocusAuto" NameSpace="Standard">
		<EnumEntry Name="Off" NameSpace="Standard">
			<Value>0</Value>
		</EnumEntry>
		<EnumEntry Name="Continuous" NameSpace="Standard">
			<Value>1</Value>
		</EnumEntry>
		<pValue>FocusAutoReg</pValue>
	</Enumeration>
	<IntReg Name="FocusAutoReg">
		<Address>0xA034</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="FocusDistance" NameSpace="Standard">
		<ToolTip>Focus distance in centidiopters (0 = Infinity, 100 = 1.0 dpt, 500 = 5.0 dpt)</ToolTip>
		<pValue>FocusDistanceReg</pValue>
		<Min>0</Min>
		<Max>2000</Max>
		<Inc>1</Inc>
	</Integer>
	<IntReg Name="FocusDistanceReg">
		<Address>0xA030</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<!-- TransportLayerControl -->
	<Category Name="TransportLayerControl" NameSpace="Standard">
		<pFeature>PayloadSize</pFeature>
		<pFeature>GevSCPSPacketSize</pFeature>
		<pFeature>GevSCPD</pFeature>
		<pFeature>TLParamsLocked</pFeature>
	</Category>

	<Integer Name="PayloadSize" NameSpace="Standard">
		<pValue>PayloadSizeReg</pValue>
	</Integer>
	<IntReg Name="PayloadSizeReg">
		<Address>0xA020</Address>
		<Length>4</Length>
		<AccessMode>RO</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="GevSCPSPacketSize" NameSpace="Standard">
		<pValue>GevSCPSPacketSizeReg</pValue>
		<Min>576</Min>
		<Max>1440</Max>
		<Inc>4</Inc>
	</Integer>
	<IntReg Name="GevSCPSPacketSizeReg">
		<Address>0x0D04</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="GevSCPD" NameSpace="Standard">
		<pValue>GevSCPDReg</pValue>
		<Min>0</Min>
		<Max>100000</Max>
		<Inc>1</Inc>
	</Integer>
	<IntReg Name="GevSCPDReg">
		<Address>0x0D08</Address>
		<Length>4</Length>
		<AccessMode>RW</AccessMode>
		<pPort>Device</pPort>
		<Sign>Unsigned</Sign>
		<Endianess>BigEndian</Endianess>
	</IntReg>

	<Integer Name="TLParamsLocked">
		<Value>0</Value>
		<Min>0</Min>
		<Max>1</Max>
	</Integer>

	<Port Name="Device" NameSpace="Standard"/>
</RegisterDescription>"""

    val XML_BYTES: ByteArray = XML_DATA.toByteArray(Charsets.UTF_8)
    const val XML_START_ADDRESS = 0x00010000
}
