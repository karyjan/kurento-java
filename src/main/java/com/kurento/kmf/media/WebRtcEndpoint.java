package com.kurento.kmf.media;

import com.kurento.tool.rom.RemoteClass;
import com.kurento.tool.rom.server.Param;

@RemoteClass
public interface WebRtcEndpoint extends SdpEndpoint {

	public interface Factory {

		public Builder create(
				@Param("mediaPipeline") MediaPipeline mediaPipeline);
	}

	public interface Builder extends AbstractBuilder<WebRtcEndpoint> {

		public Builder withGarbagePeriod(int garbagePeriod);
	}
}