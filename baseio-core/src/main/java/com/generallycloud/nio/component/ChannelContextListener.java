/*
 * Copyright 2015-2017 GenerallyCloud.com
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */ 
package com.generallycloud.nio.component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.generallycloud.nio.AbstractLifeCycleListener;
import com.generallycloud.nio.LifeCycle;
import com.generallycloud.nio.LifeCycleListener;
import com.generallycloud.nio.common.Logger;
import com.generallycloud.nio.common.LoggerFactory;
import com.generallycloud.nio.common.LoggerUtil;
import com.generallycloud.nio.configuration.ServerConfiguration;

public class ChannelContextListener extends AbstractLifeCycleListener implements LifeCycleListener {

	private Logger		logger		= LoggerFactory.getLogger(ChannelContextListener.class);

	@Override
	public int lifeCycleListenerSortIndex() {
		return 999;
	}

	@Override
	public void lifeCycleStarted(LifeCycle lifeCycle) {
//		LoggerUtil.prettyNIOServerLog(logger, "CONTEXT加载完成");
	}

	@Override
	public void lifeCycleFailure(LifeCycle lifeCycle, Exception exception) {
		// NIOConnector connector = (NIOConnector) lifeCycle;
		logger.error(exception.getMessage(), exception);
	}

	@Override
	public void lifeCycleStopped(LifeCycle lifeCycle) {
		LoggerUtil.prettyNIOServerLog(logger, "服务停止成功");
	}

	@Override
	public void lifeCycleStopping(LifeCycle lifeCycle) {
		ChannelContext context = (ChannelContext) lifeCycle;
		
		if (context == null) {
			LoggerUtil.prettyNIOServerLog(logger, "服务启动失败，正在停止...");
			return;
		}
		
		ServerConfiguration configuration = context.getServerConfiguration();
		
		BigDecimal time = new BigDecimal(System.currentTimeMillis() - context.getStartupTime());
		BigDecimal anHour = new BigDecimal(60 * 60 * 1000);
		BigDecimal hour = time.divide(anHour, 3, RoundingMode.HALF_UP);
		String[] params = { String.valueOf(configuration.getSERVER_PORT()), String.valueOf(hour) };
		LoggerUtil.prettyNIOServerLog(logger, "服务运行时间  @127.0.0.1:{} 共 {} 小时", params);
		LoggerUtil.prettyNIOServerLog(logger, "开始停止服务，请稍等");
	}

}
