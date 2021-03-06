/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package org.kurento.test.latency;

import java.awt.Color;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.kurento.test.base.KurentoTest;
import org.kurento.test.client.BrowserClient;
import org.kurento.test.monitor.SystemMonitorManager;
import org.openqa.selenium.JavascriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Latency controller.
 * 
 * @author Micael Gallego (micael.gallego@gmail.com)
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.5
 */
public class LatencyController implements
		ChangeColorEventListener<ChangeColorEvent> {

	private static final int MAX_DISTANCE = 60;

	public Logger log = LoggerFactory.getLogger(LatencyController.class);

	private Map<Long, LatencyRegistry> latencyMap;

	private String name;

	private long latencyThreshold;
	private TimeUnit latencyThresholdTimeUnit;

	private long timeout;
	private TimeUnit timeoutTimeUnit;

	private ChangeColorObservable localChangeColor;
	private ChangeColorObservable remoteChangeColor;

	private long lastLocalColorChangeTime = -1;
	private long lastRemoteColorChangeTime = -1;
	private long lastLocalColorChangeTimeAbsolute = -1;
	private long lastRemoteColorChangeTimeAbsolute = -1;

	private String lastLocalColor;
	private String lastRemoteColor;

	private Thread localColorTrigger;
	private Thread remoteColorTrigger;

	private Semaphore localEventLatch = new Semaphore(0);
	private Semaphore remoteEventLatch = new Semaphore(0);

	private boolean failIfLatencyProblem;
	private boolean local;

	private BrowserClient localBrowser;

	private long latencyRate;

	private int consecutiveFailMax;

	public LatencyController(String name) {
		this();
		this.name = name;
	}

	public LatencyController() {
		// Defaults
		latencyThreshold = 3000;
		latencyThresholdTimeUnit = TimeUnit.MILLISECONDS;

		timeout = 30;
		timeoutTimeUnit = TimeUnit.SECONDS;

		failIfLatencyProblem = false;
		local = true;

		latencyRate = 100; // milliseconds

		consecutiveFailMax = 3;

		// Latency map (registry)
		latencyMap = new TreeMap<Long, LatencyRegistry>();
	}

	@Override
	public synchronized void onEvent(ChangeColorEvent e) {
		if (e.getVideoTag().getVideoTagType() == VideoTagType.LOCAL) {
			lastLocalColorChangeTimeAbsolute = new Date().getTime();
			lastLocalColorChangeTime = e.getTime();
			lastLocalColor = e.getColor();
			localEventLatch.release();
		} else if (e.getVideoTag().getVideoTagType() == VideoTagType.REMOTE) {
			lastRemoteColorChangeTimeAbsolute = new Date().getTime();
			lastRemoteColorChangeTime = e.getTime();
			lastRemoteColor = e.getColor();
			remoteEventLatch.release();
		}
	}

	public void checkLatency(long testTime, TimeUnit testTimeUnit,
			SystemMonitorManager monitor) throws InterruptedException,
			IOException {
		checkRemoteLatency(testTime, testTimeUnit, monitor);
	}

	public void checkLatency(long testTime, TimeUnit testTimeUnit)
			throws InterruptedException, IOException {
		if (local) {
			checkLocalLatency(testTime, testTimeUnit);
		} else {
			checkRemoteLatency(testTime, testTimeUnit, null);
		}
	}

	public void checkLatencyInBackground(final long testTime,
			final TimeUnit testTimeUnit) throws InterruptedException,
			IOException {
		new Thread() {
			public void run() {
				try {
					checkLatency(testTime, testTimeUnit);
				} catch (InterruptedException e1) {
					log.warn(
							"checkLatencyInBackground InterruptedException: {}",
							e1.getMessage());
				} catch (IOException e2) {
					throw new RuntimeException(e2);
				}
			}
		}.start();
	}

	public void checkLocalLatency(final long testTime,
			final TimeUnit testTimeUnit) throws InterruptedException,
			IOException {
		long playTime = TimeUnit.MILLISECONDS.convert(testTime, testTimeUnit);
		long endTimeMillis = System.currentTimeMillis() + playTime;
		int consecutiveFailCounter = 0;
		boolean first = true;
		while (true) {
			if (System.currentTimeMillis() > endTimeMillis) {
				break;
			}
			Thread.sleep(latencyRate);

			long latency = 0;
			LatencyRegistry latencyRegistry = new LatencyRegistry();
			try {
				latency = localBrowser.getLatency();
				if (latency == Long.MIN_VALUE) {
					continue;
				}
				if (first) {
					// First latency measurement is discarded
					first = false;
					continue;
				}
				log.info(">>> Latency adquired: {} ms", latency);

			} catch (LatencyException le) {
				latencyRegistry.setLatencyException(le);

				if (failIfLatencyProblem) {
					throw le;
				}
			}

			long latencyTime = localBrowser.getRemoteTime();
			latencyRegistry.setLatency(latency);

			if (latency > getLatencyThreshold(TimeUnit.MILLISECONDS)) {

				String parsedtime = new SimpleDateFormat("mm-ss.SSS")
						.format(latencyTime);
				localBrowser.takeScreeshot(KurentoTest.getDefaultOutputFile("-"
						+ parsedtime + "-error-screenshot.png"));

				LatencyException latencyException = new LatencyException(
						latency, TimeUnit.MILLISECONDS);

				latencyRegistry.setLatencyException(latencyException);
				if (failIfLatencyProblem) {
					throw latencyException;
				}

				consecutiveFailCounter++;
				if (consecutiveFailCounter >= consecutiveFailMax) {
					throw new RuntimeException(consecutiveFailMax
							+ " consecutive latency errors detected. Latest: "
							+ latencyException.getLocalizedMessage());
				}
			} else {
				// Reset the consecutive fail counter
				consecutiveFailCounter = 0;
			}
			latencyMap.put(latencyTime, latencyRegistry);
		}
	}

	public void checkRemoteLatency(SystemMonitorManager monitor) {
		checkRemoteLatency(0, null, monitor);
	}

	public void checkRemoteLatency(final long testTime,
			final TimeUnit testTimeUnit, SystemMonitorManager monitor) {
		String msgName = (name != null) ? "[" + name + "] " : "";

		if (localChangeColor == null || remoteChangeColor == null) {
			throw new RuntimeException(msgName
					+ "Bad setup in latency controller "
					+ " (local and remote tag of browser(s) needed");
		}

		try {
			final Thread waitingThread = Thread.currentThread();

			Thread t;
			if (testTimeUnit != null) {
				t = new Thread() {
					public void run() {
						try {
							testTimeUnit.sleep(testTime);
							waitingThread.interrupt();
						} catch (InterruptedException e) {
						}
					}
				};
				t.setDaemon(true);
				t.start();
			} else {
				t = waitingThread;
			}

			// Synchronization with the green color
			do {
				waitForLocalColor(msgName, t);
			} while (!similarColor(lastLocalColor, "0,255,0,0"));
			do {
				waitForRemoteColor(msgName, t);
			} while (!similarColor(lastRemoteColor, "0,255,0,0"));

			while (true) {

				waitForLocalColor(msgName, t);
				waitForRemoteColor(msgName, t);

				long latencyMilis = Math.abs(lastRemoteColorChangeTimeAbsolute
						- lastLocalColorChangeTimeAbsolute);

				SimpleDateFormat formater = new SimpleDateFormat("mm:ss.SSS");
				String parsedLocaltime = formater
						.format(lastLocalColorChangeTimeAbsolute);
				String parsedRemotetime = formater
						.format(lastRemoteColorChangeTimeAbsolute);

				log.info(
						"latencyMilis={} -- lastLocalColor={} -- lastRemoteColor={} -- "
								+ "lastLocalColorChangeTime={} -- lastRemoteColorChangeTime={} -- "
								+ "lastLocalColorChangeTimeAbsolute={} -- lastRemoteColorChangeTimeAbsolute={}",
						latencyMilis, lastLocalColor, lastRemoteColor,
						formater.format(lastLocalColorChangeTime),
						formater.format(lastRemoteColorChangeTime),
						parsedLocaltime, parsedRemotetime);

				if (similarColor(lastLocalColor, lastRemoteColor)) {
					log.info("--> Latency adquired ({} ms)", latencyMilis);

					if (monitor != null) {
						monitor.addCurrentLatency(latencyMilis);
					}

					LatencyRegistry LatencyRegistry = new LatencyRegistry(
							rgba2Color(lastRemoteColor), latencyMilis);

					if (latencyMilis > getLatencyThreshold(TimeUnit.MILLISECONDS)) {
						LatencyException latencyException = new LatencyException(
								latencyMilis, testTimeUnit, parsedLocaltime,
								parsedRemotetime, testTime, latencyMilis);
						LatencyRegistry.setLatencyException(latencyException);
						if (failIfLatencyProblem) {
							t.interrupt();
							throw latencyException;
						} else {
							log.warn(latencyException.getMessage());
						}
						if (monitor != null) {
							monitor.incrementLatencyErrors();
						}
					}

					latencyMap.put(lastRemoteColorChangeTime, LatencyRegistry);
				}

			}

		} catch (IOException e) {
			log.debug("Finished LatencyController thread due to IO Exception");
		} catch (InterruptedException e) {
			log.debug("Finished LatencyController thread due to Interrupted Exception");
		}
		localColorTrigger.interrupt();
		remoteColorTrigger.interrupt();
	}

	private void waitForRemoteColor(String msgName, Thread t)
			throws InterruptedException {
		if (!remoteEventLatch.tryAcquire(timeout, timeoutTimeUnit)) {
			t.interrupt();
			throw new RuntimeException(msgName
					+ "Change color not detected in REMOTE steam after "
					+ timeout + " " + timeoutTimeUnit);
		}
	}

	private void waitForLocalColor(String msgName, Thread t)
			throws InterruptedException {
		if (!localEventLatch.tryAcquire(timeout, timeoutTimeUnit)) {
			t.interrupt();

			throw new RuntimeException(msgName
					+ "Change color not detected in LOCAL steam after "
					+ timeout + " " + timeoutTimeUnit);
		}
	}

	private boolean similarColor(String expectedColorStr, String realColorStr) {
		String[] realColor = realColorStr.split(",");
		int realRed = Integer.parseInt(realColor[0]);
		int realGreen = Integer.parseInt(realColor[1]);
		int realBlue = Integer.parseInt(realColor[2]);

		String[] expectedColor = expectedColorStr.split(",");
		int expectedRed = Integer.parseInt(expectedColor[0]);
		int expectedGreen = Integer.parseInt(expectedColor[1]);
		int expectedBlue = Integer.parseInt(expectedColor[2]);

		double distance = Math.sqrt((realRed - expectedRed)
				* (realRed - expectedRed) + (realGreen - expectedGreen)
				* (realGreen - expectedGreen) + (realBlue - expectedBlue)
				* (realBlue - expectedBlue));
		return distance <= MAX_DISTANCE;
	}

	public void addChangeColorEventListener(VideoTag type,
			JavascriptExecutor js, String name) {
		final long timeoutSeconds = TimeUnit.SECONDS.convert(timeout,
				timeoutTimeUnit);

		if (type.getVideoTagType() == VideoTagType.LOCAL) {
			localChangeColor = new ChangeColorObservable();
			localChangeColor.addListener(this);
			localColorTrigger = new Thread(new ColorTrigger(type, js,
					localChangeColor, timeoutSeconds));
			if (name != null) {
				localColorTrigger.setName(name);
			}
			localColorTrigger.start();
		} else {
			remoteChangeColor = new ChangeColorObservable();
			remoteChangeColor.addListener(this);
			remoteColorTrigger = new Thread(new ColorTrigger(type, js,
					remoteChangeColor, timeoutSeconds));
			if (name != null) {
				remoteColorTrigger.setName(name);
			}
			remoteColorTrigger.start();
		}
	}

	public void drawChart(String filename, int width, int height)
			throws IOException {
		ChartWriter chartWriter = new ChartWriter(latencyMap, getName());
		chartWriter.drawChart(filename, width, height);
	}

	public void writeCsv(String csvTitle) throws IOException {
		PrintWriter pw = new PrintWriter(new FileWriter(csvTitle));
		for (long time : latencyMap.keySet()) {
			pw.println(time + "," + latencyMap.get(time).getLatency());
		}
		pw.close();
	}

	public void logLatencyErrorrs() throws IOException {
		log.debug("---------------------------------------------");
		log.debug("LATENCY ERRORS " + getName());
		log.debug("---------------------------------------------");
		int nErrors = 0;
		for (LatencyRegistry registry : latencyMap.values()) {
			if (registry.isLatencyError()) {
				nErrors++;
				log.debug(registry.getLatencyException().getMessage());
			}
		}

		log.debug("{} errors of latency detected (threshold: {} {})", nErrors,
				latencyThreshold, latencyThresholdTimeUnit);
		log.debug("---------------------------------------------");
	}

	public long getLatencyThreshold(TimeUnit timeUnit) {
		return timeUnit.convert(latencyThreshold, latencyThresholdTimeUnit);
	}

	public long getLatencyThreshold() {
		return latencyThreshold;
	}

	public void setLatencyThreshold(long latencyThreshold,
			TimeUnit latencyThresholdTimeUnit) {
		this.latencyThreshold = latencyThreshold;
		this.latencyThresholdTimeUnit = latencyThresholdTimeUnit;
	}

	public TimeUnit getLatencyTimeUnit() {
		return latencyThresholdTimeUnit;
	}

	public long getTimeout() {
		return timeout;
	}

	public void setTimeout(long timeout, TimeUnit timeoutTimeUnit) {
		this.timeout = timeout;
		this.timeoutTimeUnit = timeoutTimeUnit;
	}

	public TimeUnit getTimeoutTimeUnit() {
		return timeoutTimeUnit;
	}

	private Color rgba2Color(String rgba) {
		String[] rgbaArr = rgba.split(",");
		return new Color(Integer.parseInt(rgbaArr[0]),
				Integer.parseInt(rgbaArr[1]), Integer.parseInt(rgbaArr[2]));
	}

	public void failIfLatencyProblem() {
		this.failIfLatencyProblem = true;
	}

	public String getName() {
		return name != null ? name : "";
	}

	public void activateLocalLatencyAssessmentIn(BrowserClient browser) {
		local = true;
		localBrowser = browser;
	}

	public void activateRemoteLatencyAssessmentIn(VideoTag videoTaglocal,
			BrowserClient browserLocal, VideoTag videoTagRemote,
			BrowserClient browserRemote) {
		local = false;

		addChangeColorEventListener(videoTaglocal,
				(JavascriptExecutor) browserLocal.getWebDriver(), getName()
						+ " " + videoTaglocal);
		addChangeColorEventListener(videoTagRemote,
				(JavascriptExecutor) browserRemote.getWebDriver(), getName()
						+ " " + videoTagRemote);
	}

	public void activateRemoteLatencyAssessmentIn(BrowserClient browserLocal,
			BrowserClient browserRemote) {
		local = false;

		addChangeColorEventListener(new VideoTag(VideoTagType.LOCAL),
				(JavascriptExecutor) browserLocal.getWebDriver(), getName()
						+ " " + VideoTagType.LOCAL);
		addChangeColorEventListener(new VideoTag(VideoTagType.REMOTE),
				(JavascriptExecutor) browserRemote.getWebDriver(), getName()
						+ " " + VideoTagType.REMOTE);
	}

	public void setLatencyRate(long latencyRate) {
		this.latencyRate = latencyRate;
	}

	public void setConsecutiveFailMax(int consecutiveFailMax) {
		this.consecutiveFailMax = consecutiveFailMax;
	}

}
